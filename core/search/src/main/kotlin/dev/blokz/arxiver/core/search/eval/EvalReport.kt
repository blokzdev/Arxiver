package dev.blokz.arxiver.core.search.eval

/**
 * One labeled paper for the offline ranker eval (P5.1). [weight] is the PU down-weighting — a dismiss is a
 * revealed "not now", not a clean negative, so it carries less evidence than a save or an explicit thumb.
 * [titleOnly] is the single causal segment axis (P-Explorer census: SSRN ~100% / recent Research Square ~86%
 * abstract-stripped → their vectors embed `"$title\n"` only, a systematically different register from rich
 * arXiv passages).
 */
data class LabeledExample(
    val paperId: String,
    val vector: FloatArray,
    val positive: Boolean,
    val weight: Double,
    val titleOnly: Boolean,
    val createdAt: Long,
) {
    // Identity by paperId — FloatArray's default equals is reference-based and would poison de-dup/asserts.
    override fun equals(other: Any?): Boolean = other is LabeledExample && other.paperId == paperId

    override fun hashCode(): Int = paperId.hashCode()
}

/**
 * A segment's verdict. [Insufficient] is a first-class result, not an error: below the effective-sample-size
 * floor a metric would be noise wearing a number, and the honest output is "can't tell yet".
 */
sealed interface SegmentResult {
    data class Measured(
        val precisionAtK: Double,
        val auc: Double,
        /** Percentile-bootstrap CI on [auc] (stratified by class), seeded + deterministic. */
        val aucLow: Double,
        val aucHigh: Double,
        /** Brier score (weighted mean squared error) — reported at every measured size. */
        val brier: Double,
        /** Expected calibration error over quantile bins — null below [RankerEval.ECE_MIN_LABELS] labels. */
        val ece: Double?,
        /** Kish effective sample sizes actually behind this verdict. */
        val essPositives: Double,
        val essNegatives: Double,
    ) : SegmentResult

    data class Insufficient(
        val essPositives: Double,
        val essNegatives: Double,
    ) : SegmentResult
}

/** Per-segment score-distribution snapshot — the label-free day-one tripwire over the ACTIVE inbox. */
data class ScoreDistribution(
    val count: Int,
    val mean: Double,
    val p50: Double,
    val p90: Double,
    /** Fraction of the segment at/above the operative "Likely relevant" cut. */
    val aboveCut: Double,
)

/**
 * The harness output (P5.1). [regimeContaminated] flags a fold mix where >30% of folds crossed a model regime
 * (seeded↔unseeded at the 10-positive gate, or negative-centroid on↔off at the 3-negative gate) — metrics from
 * mixed regimes describe two different rankers and must not gate a promotion.
 */
data class EvalReport(
    val overall: SegmentResult,
    val rich: SegmentResult,
    val titleOnly: SegmentResult,
    val regimeContaminated: Boolean,
    val labelCount: Int,
    val evaluatedAtMs: Long,
)

/**
 * The production bundle (P5.3): the tuned report + the selected shrinkage + the pooled HELD-OUT fold outputs
 * (parallel lists) that downstream calibration fits on.
 */
data class TunedEval(
    val report: EvalReport,
    val lambda: Double,
    val heldOutScores: List<Double>,
    val heldOutLabels: List<Boolean>,
    val heldOutWeights: List<Double>,
)
