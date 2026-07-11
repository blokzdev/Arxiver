package dev.blokz.arxiver.sync

import dev.blokz.arxiver.core.database.dao.EmbeddingDao
import dev.blokz.arxiver.core.database.dao.InboxDao
import dev.blokz.arxiver.core.database.dao.PaperFeedbackDao
import dev.blokz.arxiver.core.database.dao.RelevanceModelDao
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity
import dev.blokz.arxiver.core.database.entity.PaperFeedbackEntity
import dev.blokz.arxiver.core.database.entity.RelevanceModelEntity
import dev.blokz.arxiver.core.search.eval.EvalReport
import dev.blokz.arxiver.core.search.eval.LabeledExample
import dev.blokz.arxiver.core.search.eval.PlattCalibration
import dev.blokz.arxiver.core.search.eval.PlattMap
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
    /** The per-user shrinkage the harness selected+confirmed this pass (0.0 = bit-identical scoring). */
    val shrinkageLambda: Double,
)

/**
 * The live tuning the scorer applies (P5.2). In-memory, default λ=0 — a process death falls back to
 * bit-identical-to-P4 scoring until the next worker pass re-selects (durable tuning arrives with P5.3's
 * `relevance_model` row). Selected ONLY by the harness; never a shipped constant.
 */
@Singleton
class RankerTuning
    @Inject
    constructor() {
        @Volatile
        var shrinkageLambda: Double = 0.0
            internal set
    }

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
        private val tuning: RankerTuning,
        private val relevanceModelDao: RelevanceModelDao,
    ) {
        suspend fun run(legacyThreshold: Double) {
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

            // Stale-embedder discard first (mirrors the vector-tag guard): a model fit under another embedding
            // never applies to this one's scores.
            relevanceModelDao.deleteByModelMismatch(EmbeddingWorker.MODEL_NAME)

            // The debug card's "above cut %" must describe the SAME cut Today drew (P5.3 QW). The distributions
            // below are the PREVIOUS pass's inbox scores (this runner executes before scoreInbox), so the cut in
            // force while they were on screen is the PREVIOUSLY persisted calibration — read it here, before this
            // pass's upsert clobbers it. a>0 is the fitter's invariant (PlattCalibration), mirroring how
            // TodayViewModel derives its live cut; absent/uncalibrated ⇒ the caller's legacy 0.55.
            val previous = relevanceModelDao.current()
            val effectiveCut =
                previous?.calibrationA?.let { a ->
                    previous.calibrationB?.let { b -> PlattMap(a, b).scoreFor(0.5) }
                } ?: legacyThreshold

            // ONE set of fold builds yields the selected λ, the tuned report, and the held-out outputs the
            // Platt calibrator fits on (P5.3) — the ≤6-rebuild cost bound covers the whole pipeline.
            val tuned = RankerEval().tuneAndEvaluate(examples, seeds, Instant.now().toEpochMilli())
            val lambda = tuned.lambda
            tuning.shrinkageLambda = lambda
            val report = tuned.report

            // Fit the calibration on HELD-OUT scores; below the floor it stays null and the UI keeps the
            // legacy 0.55 exactly. Persisted durably so Today's threshold survives process death.
            val platt = PlattCalibration.fit(tuned.heldOutScores, tuned.heldOutLabels, tuned.heldOutWeights)
            relevanceModelDao.upsert(
                RelevanceModelEntity(
                    embeddingModel = EmbeddingWorker.MODEL_NAME,
                    calibrationA = platt?.a,
                    calibrationB = platt?.b,
                    shrinkageLambda = lambda,
                    labelPositives = tuned.heldOutLabels.count { it },
                    labelNegatives = tuned.heldOutLabels.count { !it },
                    fittedAt = Instant.now().toEpochMilli(),
                ),
            )

            // NOTE: these distributions describe the PREVIOUS pass's scores — the runner executes before
            // scoreInbox so a freshly selected λ applies in the same worker run.
            val scores = inboxDao.activeScoresBySegment()
            state.publish(
                RankerHealth(
                    report = report,
                    richDistribution =
                        distribution(
                            scores.filter { !it.titleOnly }.map { it.score },
                            effectiveCut,
                        ),
                    titleOnlyDistribution =
                        distribution(scores.filter { it.titleOnly }.map { it.score }, effectiveCut),
                    shrinkageLambda = lambda,
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
