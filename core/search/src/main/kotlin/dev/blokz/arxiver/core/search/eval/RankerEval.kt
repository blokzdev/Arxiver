package dev.blokz.arxiver.core.search.eval

import dev.blokz.arxiver.core.search.KMeans
import dev.blokz.arxiver.core.search.RocchioRanker
import kotlin.math.abs
import kotlin.random.Random

/**
 * Offline, on-device eval of the live Rocchio inbox ranker over the user's OWN labels (P5.1) — the ruler every
 * later P5 promotion (shrinkage λ, calibration, the gated learned head's β) must clear. Pure and deterministic:
 * no clock, no I/O, no network; the caller supplies labels, seed vectors, and the timestamp.
 *
 * Design commitments (each pinned by a test):
 * - **Fold contract:** a fold's model is a pure function of `(trainPositives, trainNegatives, seedVectors)` with
 *   the held-out ids filtered from ALL THREE — including the cold-start seed, whose re-entry was the leak nobody
 *   named (a held-out save can also be an active-inbox seed row).
 * - **Time-split primary** (one k-means rebuild — oldest labels train, newest test: the deployment direction);
 *   stratified k-fold only when the data clears the floor, so cost is bounded at ≤ [KFOLDS]+1 rebuilds.
 * - **Floors are Kish effective sample size, not raw rows** — PU down-weighting means 14 dismisses ≈ ESS 6;
 *   raw counts would launder weighted noise into a verdict.
 * - **Segmentation on one causal axis** (`titleOnly` — abstract-stripped papers embed `"$title\n"` only): a flat
 *   aggregate can hide one segment starved below the cut and another polluting above it.
 * - **Metrics degrade honestly:** Brier always (binless); ECE only at ≥ [ECE_MIN_LABELS] labels with quantile
 *   bins; the AUC ships with a seeded stratified percentile-bootstrap CI over FROZEN fold outputs (never retrains).
 * - **Regime honesty:** a fold that crosses a model regime (seeded↔unseeded at the 10-positive gate; negative
 *   centroid on↔off at 3) describes a *different ranker*; >30% crossing flags the report as contaminated.
 */
class RankerEval(
    private val seed: Long = 42L,
    private val precisionK: Int = 10,
    private val bootstrapResamples: Int = 2_000,
    /** Injectable ONLY so a test can count rebuilds — the cost bound (≤ folds) is a product requirement. */
    private val centroidsFn: (List<FloatArray>, Int) -> List<FloatArray> = KMeans::centroids,
) {
    /** A frozen per-example fold output — everything downstream (metrics, bootstrap) reads only these. */
    private data class Scored(
        val score: Double,
        val positive: Boolean,
        val weight: Double,
        val titleOnly: Boolean,
    )

    /** One built fold: the model's parts (built ONCE) + its held-out test set. */
    private data class Fold(
        val centroids: List<FloatArray>,
        val negative: FloatArray?,
        val regime: Pair<Boolean, Boolean>,
        val test: List<LabeledExample>,
        val isTimeSplit: Boolean,
    )

    private fun cap(examples: List<LabeledExample>): List<LabeledExample> =
        examples.sortedByDescending { it.createdAt }.take(LABEL_CAP).sortedBy { it.createdAt }

    /** Build every fold's model exactly once — shrinkage variants re-score these, never rebuild. */
    private fun builtFolds(
        capped: List<LabeledExample>,
        seedVectors: Map<String, FloatArray>,
    ): List<Fold> {
        val raw = mutableListOf<Triple<List<LabeledExample>, List<LabeledExample>, Boolean>>()
        timeSplit(capped)?.let { raw += Triple(it.first, it.second, true) }
        if (meetsKFoldFloor(capped)) raw += stratifiedKFold(capped).map { Triple(it.first, it.second, false) }
        return raw.mapNotNull { (train, test, isTime) ->
            val heldOut = test.map { it.paperId }.toSet()
            val model = buildModel(train, seedVectors.filterKeys { it !in heldOut }) ?: return@mapNotNull null
            Fold(model.centroids, model.negative, model.regime, test, isTime)
        }
    }

    private fun score(
        fold: Fold,
        lambda: Double,
    ): List<Scored> {
        val centroids = RocchioRanker.shrinkCentroids(fold.centroids, lambda)
        return fold.test.map { ex ->
            Scored(RocchioRanker.score(ex.vector, centroids, fold.negative), ex.positive, ex.weight, ex.titleOnly)
        }
    }

    fun evaluate(
        examples: List<LabeledExample>,
        seedVectors: Map<String, FloatArray>,
        evaluatedAtMs: Long,
        lambda: Double = 0.0,
    ): EvalReport {
        // Bound the on-device cost: the newest LABEL_CAP labels are the operative history.
        val capped = cap(examples)
        val folds = builtFolds(capped, seedVectors)
        val outputs = folds.flatMap { score(it, lambda) }
        val regimes = folds.map { it.regime }

        // Regime honesty: the PRODUCTION model (all labels + seeds) defines the ranker being shipped; any
        // scored fold that ran under a different regime (seeded↔unseeded, negative-centroid on↔off — e.g. a
        // temporally-clustered dismiss history leaving the time-split's train side negative-free) measured a
        // DIFFERENT ranker, and the report says so. (A fixed fold-fraction threshold was dead code here: the
        // ESS floor guarantees k-fold train sides never straddle the gates, so only the time split can differ.)
        val productionRegime = buildModel(capped, seedVectors)?.regime
        val contaminated = productionRegime != null && regimes.any { it != productionRegime }

        return EvalReport(
            overall = segmentResult(outputs),
            rich = segmentResult(outputs.filter { !it.titleOnly }),
            titleOnly = segmentResult(outputs.filter { it.titleOnly }),
            regimeContaminated = contaminated,
            labelCount = capped.size,
            evaluatedAtMs = evaluatedAtMs,
        )
    }

    // --- model building: replicates InboxScorer's live construction, as a pure function ---

    private class Model(
        val centroids: List<FloatArray>,
        val negative: FloatArray?,
        val regime: Pair<Boolean, Boolean>,
    )

    private fun buildModel(
        train: List<LabeledExample>,
        seeds: Map<String, FloatArray>,
    ): Model? {
        val trainIds = train.map { it.paperId }.toSet()
        val positives = train.filter { it.positive }.map { it.vector }
        val seeded = positives.size < COLD_START_MINIMUM
        val positiveVectors =
            if (seeded) positives + seeds.filterKeys { it !in trainIds }.values else positives
        if (positiveVectors.size < COLD_START_MINIMUM) return null // recency regime: nothing to evaluate
        val negatives = train.filter { !it.positive }.map { it.vector }
        val negativeCentroid = RocchioRanker.negativeCentroid(negatives)
        return Model(
            centroids = centroidsFn(positiveVectors, CENTROID_COUNT),
            negative = negativeCentroid,
            regime = seeded to (negativeCentroid != null),
        )
    }

    /**
     * Per-user shrinkage selection (P5.2): pick λ from the PRE-REGISTERED [grid] by pooled k-fold AUC, then
     * CONFIRM the winner on the temporally-disjoint time split — selection and confirmation on different splits
     * is the multiplicity guard (a grid search on one split alone would overfit it). Returns 0.0 (bit-identical
     * scoring) below the k-fold floor, when no time split exists, or when the winner fails confirmation.
     * Costs ZERO extra k-means rebuilds: shrinkage re-scores each fold's already-built centroids.
     */
    fun selectShrinkage(
        examples: List<LabeledExample>,
        seedVectors: Map<String, FloatArray>,
        grid: List<Double> = SHRINKAGE_GRID,
    ): Double {
        val capped = cap(examples)
        if (!meetsKFoldFloor(capped)) return 0.0
        return selectShrinkageOn(builtFolds(capped, seedVectors), grid)
    }

    private fun selectShrinkageOn(
        folds: List<Fold>,
        grid: List<Double>,
    ): Double {
        val kFolds = folds.filter { !it.isTimeSplit }
        val timeFold = folds.firstOrNull { it.isTimeSplit } ?: return 0.0
        if (kFolds.isEmpty()) return 0.0

        fun auc(
            fs: List<Fold>,
            lambda: Double,
        ): Double {
            val outs = fs.flatMap { score(it, lambda) }
            return weightedAuc(outs.filter { it.positive }, outs.filter { !it.positive })
        }

        val winner = grid.maxBy { auc(kFolds, it) }
        if (winner <= 0.0) return 0.0
        return if (auc(listOf(timeFold), winner) >= auc(listOf(timeFold), 0.0)) winner else 0.0
    }

    /**
     * The production entry (P5.3): one set of fold builds yields the selected λ, the report evaluated AT that
     * λ, and the pooled held-out outputs the Platt calibrator fits on (held-out — calibrating on train-side
     * scores would inherit their optimism). Keeps the ≤[KFOLDS]+1 rebuild bound for the whole pipeline.
     */
    fun tuneAndEvaluate(
        examples: List<LabeledExample>,
        seedVectors: Map<String, FloatArray>,
        evaluatedAtMs: Long,
    ): TunedEval {
        val capped = cap(examples)
        val folds = builtFolds(capped, seedVectors)
        val lambda = if (meetsKFoldFloor(capped)) selectShrinkageOn(folds, SHRINKAGE_GRID) else 0.0
        val outputs = folds.flatMap { score(it, lambda) }
        val report =
            EvalReport(
                overall = segmentResult(outputs),
                rich = segmentResult(outputs.filter { !it.titleOnly }),
                titleOnly = segmentResult(outputs.filter { it.titleOnly }),
                regimeContaminated =
                    buildModel(capped, seedVectors)?.regime
                        ?.let { prod -> folds.any { it.regime != prod } } ?: false,
                labelCount = capped.size,
                evaluatedAtMs = evaluatedAtMs,
            )
        return TunedEval(
            report = report,
            lambda = lambda,
            heldOutScores = outputs.map { it.score },
            heldOutLabels = outputs.map { it.positive },
            heldOutWeights = outputs.map { it.weight },
        )
    }

    // --- folds ---

    /** Oldest → train, newest → test (the deployment direction). Null when either side lacks both classes. */
    private fun timeSplit(examples: List<LabeledExample>): Pair<List<LabeledExample>, List<LabeledExample>>? {
        if (examples.size < 4) return null
        val cut = (examples.size * TIME_SPLIT_TRAIN).toInt().coerceIn(1, examples.size - 1)
        val train = examples.take(cut)
        val test = examples.drop(cut)
        val ok = test.any { it.positive } && test.any { !it.positive }
        return (train to test).takeIf { ok }
    }

    private fun meetsKFoldFloor(examples: List<LabeledExample>): Boolean =
        ess(examples.filter { it.positive }.map { it.weight }) >= ESS_FLOOR &&
            ess(examples.filter { !it.positive }.map { it.weight }) >= ESS_FLOOR

    /** Class-stratified k folds, seeded shuffle — each example held out exactly once. */
    private fun stratifiedKFold(
        examples: List<LabeledExample>,
    ): List<Pair<List<LabeledExample>, List<LabeledExample>>> {
        val rng = Random(seed)
        val byClass = examples.groupBy { it.positive }.mapValues { (_, v) -> v.shuffled(rng) }
        return (0 until KFOLDS).map { fold ->
            val test = byClass.values.flatMap { list -> list.filterIndexed { i, _ -> i % KFOLDS == fold } }
            val testIds = test.map { it.paperId }.toSet()
            val train = examples.filter { it.paperId !in testIds }
            train to test
        }
    }

    // --- metrics over frozen outputs ---

    private fun segmentResult(outputs: List<Scored>): SegmentResult {
        val positives = outputs.filter { it.positive }
        val negatives = outputs.filter { !it.positive }
        val essPos = ess(positives.map { it.weight })
        val essNeg = ess(negatives.map { it.weight })
        if (essPos < ESS_FLOOR || essNeg < ESS_FLOOR) return SegmentResult.Insufficient(essPos, essNeg)

        val auc = weightedAuc(positives, negatives)
        val (low, high) = bootstrapAucCi(positives, negatives)
        return SegmentResult.Measured(
            precisionAtK = precisionAtK(outputs),
            auc = auc,
            aucLow = low,
            aucHigh = high,
            brier = brier(outputs),
            ece = if (outputs.size >= ECE_MIN_LABELS) ece(outputs) else null,
            essPositives = essPos,
            essNegatives = essNeg,
        )
    }

    private fun precisionAtK(outputs: List<Scored>): Double {
        val top = outputs.sortedByDescending { it.score }.take(precisionK)
        val w = top.sumOf { it.weight }
        return if (w == 0.0) 0.0 else top.sumOf { if (it.positive) it.weight else 0.0 } / w
    }

    /** Weighted Mann–Whitney AUC (ties count half). O(P·N) — bounded by the label cap. */
    private fun weightedAuc(
        positives: List<Scored>,
        negatives: List<Scored>,
    ): Double {
        var num = 0.0
        var den = 0.0
        positives.forEach { p ->
            negatives.forEach { n ->
                val w = p.weight * n.weight
                den += w
                num +=
                    when {
                        p.score > n.score -> w
                        p.score == n.score -> w / 2
                        else -> 0.0
                    }
            }
        }
        return if (den == 0.0) 0.5 else num / den
    }

    private fun brier(outputs: List<Scored>): Double {
        val w = outputs.sumOf { it.weight }
        if (w == 0.0) return 0.0
        return outputs.sumOf { it.weight * square(it.score - if (it.positive) 1.0 else 0.0) } / w
    }

    /** ECE over quantile bins — every bin holds the same COUNT, so no bin is a single outlier's megaphone. */
    private fun ece(outputs: List<Scored>): Double {
        val sorted = outputs.sortedBy { it.score }
        val bins = sorted.chunked((sorted.size + ECE_BINS - 1) / ECE_BINS)
        val totalW = outputs.sumOf { it.weight }
        if (totalW == 0.0) return 0.0
        return bins.sumOf { bin ->
            val w = bin.sumOf { it.weight }
            if (w == 0.0) return@sumOf 0.0
            val conf = bin.sumOf { it.weight * it.score } / w
            val acc = bin.sumOf { if (it.positive) it.weight else 0.0 } / w
            (w / totalW) * abs(acc - conf)
        }
    }

    /** Percentile bootstrap on the AUC, resampling each CLASS independently over frozen outputs. Seeded. */
    private fun bootstrapAucCi(
        positives: List<Scored>,
        negatives: List<Scored>,
    ): Pair<Double, Double> {
        val rng = Random(seed)
        val samples =
            DoubleArray(bootstrapResamples) {
                val p = List(positives.size) { positives[rng.nextInt(positives.size)] }
                val n = List(negatives.size) { negatives[rng.nextInt(negatives.size)] }
                weightedAuc(p, n)
            }
        samples.sort()
        val lo = samples[(bootstrapResamples * 0.025).toInt().coerceIn(0, bootstrapResamples - 1)]
        val hi = samples[(bootstrapResamples * 0.975).toInt().coerceIn(0, bootstrapResamples - 1)]
        return lo to hi
    }

    private fun square(x: Double): Double = x * x

    companion object {
        /** Kish effective sample size: (Σw)² / Σw². The honest size of a weighted sample. */
        fun ess(weights: List<Double>): Double {
            val s = weights.sum()
            val s2 = weights.sumOf { it * it }
            return if (s2 == 0.0) 0.0 else s * s / s2
        }

        /** Mirrors InboxScorer — the eval must model exactly the ranker that ships. */
        const val COLD_START_MINIMUM = 10
        const val CENTROID_COUNT = 5

        const val ESS_FLOOR = 15.0
        const val ECE_MIN_LABELS = 50
        const val ECE_BINS = 10
        const val LABEL_CAP = 500
        const val KFOLDS = 5
        const val TIME_SPLIT_TRAIN = 0.7

        /** The pre-registered λ grid (P5.2) — fixed BEFORE any data is seen, never tuned per run. */
        val SHRINKAGE_GRID = listOf(0.0, 0.1, 0.2, 0.3)

        /** PU down-weighting: an explicit save/thumb is full evidence; a dismiss is a weak "not now". */
        const val WEIGHT_EXPLICIT = 1.0
        const val WEIGHT_DISMISS = 0.3
    }
}
