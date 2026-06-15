package dev.blokz.arxiver.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.database.dao.ChunkEmbeddingDao
import dev.blokz.arxiver.core.database.dao.EmbeddingDao
import dev.blokz.arxiver.core.database.dao.InboxDao
import dev.blokz.arxiver.core.database.dao.LibraryDao
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity
import dev.blokz.arxiver.core.database.entity.RelatedPaperEntity
import dev.blokz.arxiver.core.ml.EmbeddingService
import dev.blokz.arxiver.core.ml.ModelDownloader
import dev.blokz.arxiver.core.search.KMeans
import dev.blokz.arxiver.core.search.VectorIndex
import dev.blokz.arxiver.rag.RagIndexer
import timber.log.Timber
import java.time.Instant

/**
 * Background semantic pipeline (ARCHITECTURE §3.5): embeds un-embedded papers
 * (library first), refreshes related-papers for the library, then recomputes
 * interest centroids and inbox triage scores (SPEC-SEARCH §4–5).
 */
@HiltWorker
class EmbeddingWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val embeddingDao: EmbeddingDao,
        private val libraryDao: LibraryDao,
        private val inboxDao: InboxDao,
        private val embeddingService: EmbeddingService,
        private val modelDownloader: ModelDownloader,
        private val vectorIndex: VectorIndex,
        private val chunkEmbeddingDao: ChunkEmbeddingDao,
        private val ragIndexer: RagIndexer,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            // Model download is bound to this worker's unmetered-network constraint.
            when (modelDownloader.ensureDownloaded()) {
                is AppResult.Failure -> return Result.retry()
                is AppResult.Success -> Unit
            }

            embedPending()
            indexLibraryChunks()
            refreshRelatedPapers()
            scoreInbox()
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

        private suspend fun scoreInbox() {
            val libraryVectors =
                libraryDao.allPaperIds()
                    .mapNotNull { embeddingDao.byPaperId(it) }
                    .map { PaperEmbeddingEntity.blobToFloats(it.vector) }
            if (libraryVectors.size < COLD_START_MINIMUM) return // SPEC-SEARCH §5 cold start

            val centroids = KMeans.centroids(libraryVectors, k = CENTROID_COUNT)
            for (paperId in inboxDao.activePaperIds()) {
                if (isStopped) return
                val embedding = embeddingDao.byPaperId(paperId) ?: continue
                val score =
                    KMeans.similarityToNearest(
                        PaperEmbeddingEntity.blobToFloats(embedding.vector),
                        centroids,
                    )
                inboxDao.setScore(paperId, score)
            }
        }

        companion object {
            const val UNIQUE_PERIODIC = "embedding_periodic"
            const val UNIQUE_ONESHOT = "embedding_now"
            const val MODEL_NAME = "bge-small-en-v1.5-q8"
            private const val BATCH_SIZE = 8
            private const val CHUNK_BACKFILL_PER_RUN = 50
            private const val RELATED_COUNT = 8
            private const val CENTROID_COUNT = 5
            private const val COLD_START_MINIMUM = 10
        }
    }
