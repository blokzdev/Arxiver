package dev.blokz.arxiver.sync

import dev.blokz.arxiver.core.database.dao.EmbeddingDao
import dev.blokz.arxiver.core.database.dao.InboxDao
import dev.blokz.arxiver.core.database.dao.LibraryDao
import dev.blokz.arxiver.core.database.dao.PaperFeedbackDao
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity
import dev.blokz.arxiver.core.search.KMeans
import dev.blokz.arxiver.core.search.RocchioRanker
import javax.inject.Inject

/**
 * Two-sided inbox relevance scoring (SPEC-SEARCH §5), extracted from [EmbeddingWorker] so the ranking
 * logic is unit-testable against real DAOs without standing up the embedding/download pipeline.
 *
 * Interest is a Rocchio two-sided model: positive centroids (library saves + explicit thumbs-up) pulled
 * toward, a single pooled negative centroid (dismissed + thumbs-down, from the durable `paper_feedback`
 * table) pushed away. Scores stay in `[0,1]` so the shipped `RELEVANT_THRESHOLD = 0.55` "Likely relevant"
 * cut and the `ScoreBar` are untouched — and are **bit-identical to the previous positive-only scorer
 * whenever there are no negatives** (a purely additive deepen). Cold start: a follows-only user with a thin
 * library is seeded from the papers their enabled follows surfaced, so they get ranking instead of pure
 * recency; only a truly empty profile falls back to recency (scores left null).
 */
class InboxScorer
    @Inject
    constructor(
        private val libraryDao: LibraryDao,
        private val inboxDao: InboxDao,
        private val embeddingDao: EmbeddingDao,
        private val paperFeedbackDao: PaperFeedbackDao,
    ) {
        /**
         * Recompute every active inbox paper's relevance score. [shouldStop] is polled per paper so the
         * calling worker can honor a WorkManager stop — a partial pass is safe: every written score comes
         * from the same fully-built model, and the next run completes the remainder.
         */
        suspend fun scoreInbox(shouldStop: () -> Boolean = { false }) {
            val positiveIds = (libraryDao.allPaperIds() + paperFeedbackDao.positivePaperIds()).toSet()
            // Dedupe: a paper the user saved or thumbed-up is never also modelled as a negative.
            val negativeIds = paperFeedbackDao.negativePaperIds().toSet() - positiveIds

            val positives = positiveIds.mapNotNull { vectorFor(it) }
            val positiveVectors =
                if (positives.size >= COLD_START_MINIMUM) {
                    positives
                } else {
                    // Cold start: seed from papers the user's enabled follows surfaced (minus dislikes).
                    val seedIds = inboxDao.activeIdsFromEnabledFollows().toSet() - positiveIds - negativeIds
                    positives + seedIds.mapNotNull { vectorFor(it) }
                }
            if (positiveVectors.size < COLD_START_MINIMUM) return // true zero state → recency (scores stay null)

            val positiveCentroids = KMeans.centroids(positiveVectors, k = CENTROID_COUNT)
            val negativeCentroid = RocchioRanker.negativeCentroid(negativeIds.mapNotNull { vectorFor(it) })

            for (paperId in inboxDao.activePaperIds()) {
                if (shouldStop()) return
                val vector = vectorFor(paperId) ?: continue
                inboxDao.setScore(paperId, RocchioRanker.score(vector, positiveCentroids, negativeCentroid))
            }
        }

        /** Current-model embedding for [paperId], or null if absent or from a stale model (guards a dim-mismatch dot crash). */
        private suspend fun vectorFor(paperId: String): FloatArray? =
            embeddingDao.byPaperId(paperId)
                ?.takeIf { it.model == EmbeddingWorker.MODEL_NAME }
                ?.let { PaperEmbeddingEntity.blobToFloats(it.vector) }

        companion object {
            private const val CENTROID_COUNT = 5
            private const val COLD_START_MINIMUM = 10
        }
    }
