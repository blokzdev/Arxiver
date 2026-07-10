package dev.blokz.arxiver.sync

import dev.blokz.arxiver.core.database.dao.EmbeddingDao
import dev.blokz.arxiver.core.database.dao.InboxDao
import dev.blokz.arxiver.core.database.dao.PaperFeedbackDao
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity
import dev.blokz.arxiver.core.database.entity.PaperFeedbackEntity
import dev.blokz.arxiver.core.search.eval.EvalReport
import dev.blokz.arxiver.core.search.eval.LabeledExample
import dev.blokz.arxiver.core.search.eval.RankerEval
import dev.blokz.arxiver.core.search.eval.ScoreDistribution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The latest on-device ranker-eval result (P5.1). In-memory only, refreshed each EmbeddingWorker run — losing it
 * to process death costs nothing (the next unmetered window recomputes), and persisting it would add schema for
 * a diagnostic. The debug health card READS this; it never computes.
 */
@Singleton
class RankerEvalState
    @Inject
    constructor() {
        private val _latest = MutableStateFlow<RankerHealth?>(null)
        val latest: StateFlow<RankerHealth?> = _latest.asStateFlow()

        fun publish(health: RankerHealth) {
            _latest.value = health
        }
    }

data class RankerHealth(
    val report: EvalReport,
    val richDistribution: ScoreDistribution?,
    val titleOnlyDistribution: ScoreDistribution?,
)

/**
 * Assembles the harness inputs from the DAOs and runs [RankerEval] once per worker pass (P5.1). The eval
 * universe is **labeled examples only** — a feedback row (or a save) is exposure proof; unlabeled absence is
 * never imputed as a negative. Seeds mirror `InboxScorer`'s cold-start set so the fold contract can filter a
 * held-out label that doubles as a seed row (the re-entry leak).
 */
class RankerEvalRunner
    @Inject
    constructor(
        private val paperFeedbackDao: PaperFeedbackDao,
        private val embeddingDao: EmbeddingDao,
        private val inboxDao: InboxDao,
        private val state: RankerEvalState,
    ) {
        suspend fun run(relevantThreshold: Double) {
            val rows = paperFeedbackDao.labeledExamples(EmbeddingWorker.MODEL_NAME)
            val examples =
                rows.map { row ->
                    LabeledExample(
                        paperId = row.paperId,
                        vector = PaperEmbeddingEntity.blobToFloats(row.vector),
                        positive = row.positive,
                        weight =
                            if (row.labelSource == PaperFeedbackEntity.SOURCE_DISMISS) {
                                RankerEval.WEIGHT_DISMISS
                            } else {
                                RankerEval.WEIGHT_EXPLICIT
                            },
                        titleOnly = row.titleOnly,
                        createdAt = row.createdAt,
                    )
                }
            // The FULL production seed set — the fold contract inside RankerEval excludes train + held-out
            // ids per fold (excluding labeled ids here would break the re-entry-leak guarantee's premise).
            val seeds =
                inboxDao.activeIdsFromEnabledFollows()
                    .mapNotNull { id ->
                        embeddingDao.byPaperId(id)
                            ?.takeIf { it.model == EmbeddingWorker.MODEL_NAME }
                            ?.let { id to PaperEmbeddingEntity.blobToFloats(it.vector) }
                    }
                    .toMap()

            val report = RankerEval().evaluate(examples, seeds, Instant.now().toEpochMilli())

            val scores = inboxDao.activeScoresBySegment()
            state.publish(
                RankerHealth(
                    report = report,
                    richDistribution =
                        distribution(
                            scores.filter { !it.titleOnly }.map { it.score },
                            relevantThreshold,
                        ),
                    titleOnlyDistribution =
                        distribution(scores.filter { it.titleOnly }.map { it.score }, relevantThreshold),
                ),
            )
        }

        private fun distribution(
            scores: List<Double>,
            cut: Double,
        ): ScoreDistribution? {
            if (scores.isEmpty()) return null
            val sorted = scores.sorted()
            return ScoreDistribution(
                count = sorted.size,
                mean = sorted.average(),
                p50 = sorted[sorted.size / 2],
                p90 = sorted[((sorted.size * 9) / 10).coerceIn(0, sorted.size - 1)],
                aboveCut = sorted.count { it >= cut }.toDouble() / sorted.size,
            )
        }
    }
