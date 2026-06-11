package dev.blokz.arxiver.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.database.dao.CitationDao
import dev.blokz.arxiver.core.database.entity.CitationEdgeEntity
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.network.s2.S2LinkedPaper
import dev.blokz.arxiver.core.network.s2.SemanticScholarClient
import timber.log.Timber
import java.time.Instant

/**
 * Nightly citation-graph refresh for library papers (ARCHITECTURE §3.5):
 * Semantic Scholar lookups, gently spaced; arXiv-linked references/citations
 * become edges, unknown endpoints become stub rows (SPEC-DATA §2).
 */
@HiltWorker
class CitationSyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val citationDao: CitationDao,
        private val s2Client: SemanticScholarClient,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val staleBefore = Instant.now().minusSeconds(STALE_AFTER_S).toEpochMilli()
            val due = citationDao.papersDueForCitationSync(staleBefore, limit = NIGHTLY_BUDGET)

            for (paper in due) {
                if (isStopped) break
                when (val result = s2Client.paperByArxivId(paper.id)) {
                    is AppResult.Success -> {
                        val s2 = result.value
                        val now = Instant.now().toEpochMilli()
                        citationDao.clearEdgesFor(paper.id)

                        val references = s2.references.mapNotNull { it.toEdgeEndpoint(now) }
                        val citations = s2.citations.mapNotNull { it.toEdgeEndpoint(now) }
                        (references + citations).forEach { citationDao.insertStubPaper(it) }

                        val referenceEdges =
                            references.map {
                                CitationEdgeEntity(citingId = paper.id, citedId = it.id, fetchedAt = now)
                            }
                        val citationEdges =
                            citations.map {
                                CitationEdgeEntity(citingId = it.id, citedId = paper.id, fetchedAt = now)
                            }
                        citationDao.insertEdges(referenceEdges + citationEdges)
                        citationDao.markCitationsSynced(paper.id, s2.citationCount, now)
                    }
                    is AppResult.Failure -> {
                        Timber.w("Citation sync failed for ${paper.id}: ${result.error}")
                        // 404s are real (not every paper is in S2) — mark synced to avoid hot-looping.
                        val error = result.error
                        if (error is dev.blokz.arxiver.core.common.AppError.Upstream && error.httpCode == 404) {
                            citationDao.markCitationsSynced(paper.id, null, Instant.now().toEpochMilli())
                        }
                    }
                }
            }
            return Result.success()
        }

        /** Stub paper row for an arXiv-linked S2 endpoint; null when not arXiv-linked. */
        private fun S2LinkedPaper.toEdgeEndpoint(now: Long): PaperEntity? {
            val arxivId = externalIds?.ArXiv?.let { ArxivId.parse(it)?.first } ?: return null
            return PaperEntity(
                id = arxivId.value,
                latestVersion = 1,
                title = title ?: arxivId.value,
                abstract = "",
                publishedAt = 0,
                updatedAt = 0,
                primaryCategory = "",
                authorsLine = "",
                comment = null,
                journalRef = null,
                doi = null,
                pdfUrl = arxivId.pdfUrl(),
                citationCount = null,
                s2PaperId = paperId,
                source = "S2_STUB",
                fetchedAt = now,
                embeddedAt = null,
                citationsSyncedAt = null,
            )
        }

        companion object {
            const val UNIQUE_PERIODIC = "citation_sync_periodic"
            private const val NIGHTLY_BUDGET = 50
            private const val STALE_AFTER_S = 7L * 24 * 3600
        }
    }
