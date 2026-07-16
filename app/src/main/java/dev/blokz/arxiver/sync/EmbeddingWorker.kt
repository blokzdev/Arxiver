package dev.blokz.arxiver.sync

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.database.dao.ChunkEmbeddingDao
import dev.blokz.arxiver.core.database.dao.EmbeddingDao
import dev.blokz.arxiver.core.database.dao.LibraryDao
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity
import dev.blokz.arxiver.core.database.entity.RelatedPaperEntity
import dev.blokz.arxiver.core.ml.EmbeddingService
import dev.blokz.arxiver.core.ml.ModelDownloader
import dev.blokz.arxiver.core.search.VectorIndex
import dev.blokz.arxiver.data.PdfBodyStore
import dev.blokz.arxiver.rag.BodyIndexer
import dev.blokz.arxiver.rag.RagIndexer
import dev.blokz.arxiver.widget.TodayWidget
import timber.log.Timber
import java.time.Instant

/**
 * Background semantic pipeline (ARCHITECTURE §3.5): embeds un-embedded papers
 * (library first), refreshes related-papers for the library, then hands off to
 * [InboxScorer] for two-sided inbox triage scores (SPEC-SEARCH §4–5).
 */
@HiltWorker
class EmbeddingWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val embeddingDao: EmbeddingDao,
        private val libraryDao: LibraryDao,
        private val inboxScorer: InboxScorer,
        private val embeddingService: EmbeddingService,
        private val modelDownloader: ModelDownloader,
        private val vectorIndex: VectorIndex,
        private val chunkEmbeddingDao: ChunkEmbeddingDao,
        private val ragIndexer: RagIndexer,
        private val bodyIndexer: BodyIndexer,
        private val pdfBodyStore: PdfBodyStore,
        private val paperDao: PaperDao,
        private val rankerEvalRunner: RankerEvalRunner,
        private val digestRunner: DigestRunner,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            // Model download is bound to this worker's unmetered-network constraint.
            when (modelDownloader.ensureDownloaded()) {
                is AppResult.Failure -> return Result.retry()
                is AppResult.Success -> Unit
            }

            // Paper-level stale-model wipe (P5.1) — the guard vectorFor() relies on but nothing enforced:
            // without it a MODEL_NAME bump leaves every paper vector tag-mismatched and the feed permanently
            // unranked. Marks clear FIRST (the query reads the rows the delete removes).
            embeddingDao.clearMarksForModelMismatch(MODEL_NAME)
            embeddingDao.deleteByModelMismatch(MODEL_NAME)

            embedPending()
            indexLibraryChunks()
            indexBodyChunks()
            refreshRelatedPapers()
            // The offline ranker eval runs BEFORE scoring (P5.2): it selects this pass's shrinkage λ, so a
            // fresh selection applies immediately (its inbox-distribution snapshot therefore describes the
            // previous pass — documented on the runner). Diagnostic + tuning only: it must never fail the worker.
            if (!isStopped) runCatching { rankerEvalRunner.run(RELEVANT_THRESHOLD) }
            inboxScorer.scoreInbox { isStopped }
            // Ambient digest (P-Ambient PA.1b): fire AFTER scoring so the count/cut are this pass's products.
            // A side-effect must never fail the worker; user-initiated passes suppress it (they're already looking).
            if (!isStopped) {
                runCatching { digestRunner.maybePost(suppressed = inputData.getBoolean(SUPPRESS_DIGEST, false)) }
            }
            // Refresh the "Likely relevant" home-screen widget (P-Ambient PA.2) with this pass's top-k — same
            // scoring pass, zero extra wakeups. `updateAll` no-ops when no widget is placed; never fail the worker.
            runCatching { TodayWidget().updateAll(applicationContext) }
            return Result.success()
        }

        /**
         * Eager RAG backfill (SPEC-SEARCH §8): chunk-embed library papers that have
         * no current-model chunks yet, bounded per run so a large library spreads
         * across periodic runs. Stale-model chunks are wiped first (model guard).
         */
        private suspend fun indexLibraryChunks() {
            chunkEmbeddingDao.deleteByModelMismatch(MODEL_NAME)
            val pending = chunkEmbeddingDao.libraryPapersMissingChunks(MODEL_NAME, CHUNK_BACKFILL_PER_RUN)
            for (paperId in pending) {
                if (isStopped) return
                when (val result = ragIndexer.indexPaper(paperId)) {
                    is AppResult.Failure -> {
                        Timber.w("Chunk indexing failed for $paperId: ${result.error}")
                        return
                    }
                    is AppResult.Success -> Unit
                }
            }
        }

        /**
         * Body-text backfill (P-FullText PFT.2): filesystem-driven (enumerates the html cache, never the
         * paper table — so a paper with no reader HTML is never a candidate) + bounded per run (body chunks
         * are ~50x heavier than an abstract). The model-mismatch wipe in [indexLibraryChunks] already dropped
         * stale body chunks, so a `MODEL_NAME` bump self-heals here. A background nicety — never fails the worker.
         */
        private suspend fun indexBodyChunks() {
            if (isStopped) return
            runCatching { bodyIndexer.backfill(BODY_BACKFILL_PER_RUN) { isStopped } }
                .onFailure { Timber.w("Body chunk backfill failed: $it") }
            if (isStopped) return
            // P-Reader2 PFT.5.7: universal PDF full-text. One-time id-backfill first (recover storageIds for the
            // pre-existing downloaded PDF corpus so the filesystem-driven backfill can reach it — the sound
            // forward match: paper.storageId sanitized == the filename prefix), then the bounded PDF body
            // backfill. Runs AFTER the HTML backfill so HTML wins the shared body slot per paper (the PDF path
            // defers to a current-model HTML index). A background nicety — never fails the worker.
            runCatching {
                val bySanitized = paperDao.allStorageIds().associateBy { it.replace(SANITIZE, "_") }
                pdfBodyStore.backfillMissingIds { bySanitized[it] }
                bodyIndexer.backfillPdf(PDF_BODY_BACKFILL_PER_RUN) { isStopped }
            }.onFailure { Timber.w("PDF body backfill failed: $it") }
        }

        private suspend fun embedPending() {
            while (!isStopped) {
                val pending = embeddingDao.unembeddedPapers(limit = BATCH_SIZE)
                if (pending.isEmpty()) return
                val texts = pending.map { EmbeddingService.passageText(it.title, it.abstract) }
                when (val result = embeddingService.embedPassages(texts)) {
                    is AppResult.Success -> {
                        val now = Instant.now().toEpochMilli()
                        pending.zip(result.value).forEach { (paper, vector) ->
                            embeddingDao.upsert(
                                PaperEmbeddingEntity(
                                    paperId = paper.id,
                                    vector = PaperEmbeddingEntity.floatsToBlob(vector),
                                    model = MODEL_NAME,
                                    dim = vector.size,
                                ),
                            )
                            embeddingDao.markEmbedded(paper.id, now)
                        }
                    }
                    is AppResult.Failure -> {
                        Timber.w("Embedding batch failed: ${result.error}")
                        return
                    }
                }
            }
        }

        private suspend fun refreshRelatedPapers() {
            val now = Instant.now().toEpochMilli()
            for (paperId in libraryDao.allPaperIds()) {
                if (isStopped) return
                val embedding = embeddingDao.byPaperId(paperId) ?: continue
                val query = PaperEmbeddingEntity.blobToFloats(embedding.vector)
                val neighbors = vectorIndex.topK(query, k = RELATED_COUNT, excludeId = paperId)
                embeddingDao.clearRelatedFor(paperId)
                embeddingDao.insertRelated(
                    neighbors.map {
                        RelatedPaperEntity(
                            paperId = paperId,
                            neighborId = it.paperId,
                            similarity = it.similarity,
                            computedAt = now,
                        )
                    },
                )
            }
        }

        companion object {
            const val UNIQUE_PERIODIC = "embedding_periodic"
            const val UNIQUE_ONESHOT = "embedding_now"

            /**
             * Input-data flag stamped `true` by USER-initiated passes (manual sync / embed-now) so they don't
             * fire the ambient digest — only the background periodic pass digests (PA.1b). Default false.
             */
            const val SUPPRESS_DIGEST = "suppress_digest"

            /**
             * The LEGACY "Likely relevant" cut — the fallback the ranker-health card's `aboveCut` diagnostic uses
             * only when no calibration is fitted. When a calibration exists the runner derives the live cut from
             * the persisted `relevance_model` row (matching TodayScreen), so the card describes the real UI.
             */
            const val RELEVANT_THRESHOLD = 0.55

            const val MODEL_NAME = "bge-small-en-v1.5-q8"
            private const val BATCH_SIZE = 8
            private const val CHUNK_BACKFILL_PER_RUN = 50

            /** Body backfill is far heavier per paper (a whole body → up to `TextChunker.MAX_BODY_CHUNKS`
             *  embeds) than abstract backfill, so only a few papers per background run (PFT.2). */
            private const val BODY_BACKFILL_PER_RUN = 4

            /** PDF bodies are the heaviest source + the noisiest — a smaller per-run bound than HTML's. */
            private const val PDF_BODY_BACKFILL_PER_RUN = 2

            /** Mirror PdfStorage.UNSAFE — build the sanitized-filename → storageId map for the id-backfill. */
            private val SANITIZE = Regex("""[/:]""")
            private const val RELATED_COUNT = 8
        }
    }
