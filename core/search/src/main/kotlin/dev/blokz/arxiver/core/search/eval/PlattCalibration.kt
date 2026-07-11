package dev.blokz.arxiver.core.search.eval

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * A fitted 2-parameter Platt map `p = σ(a·s + b)` from raw ranker scores to calibrated relevance
 * probabilities (P5.3). **Monotone** (the fitter enforces `a > 0`), so calibration NEVER reorders papers —
 * which is why the raw score stays what `inbox_items.score` persists, and the UI merely translates its
 * probability floor once: [scoreFor] maps `p_min → s_min` in closed form.
 */
data class PlattMap(
    val a: Double,
    val b: Double,
) {
    fun probabilityOf(score: Double): Double = 1.0 / (1.0 + exp(-(a * score + b)))

    /** The raw-score threshold whose calibrated probability is [p] — the inverse of the sigmoid, exact. */
    fun scoreFor(p: Double): Double {
        val clamped = p.coerceIn(1e-6, 1 - 1e-6)
        return (ln(clamped / (1 - clamped)) - b) / a
    }
}

/**
 * Fits the Platt map on the harness's FROZEN held-out outputs (P5.3) — calibrating on train-side scores would
 * inherit their optimism. Deterministic weighted Newton iterations on the 2-parameter logistic likelihood, with
 * Platt's label smoothing (the classic (n±1)/(n±2) targets) so a separable set can't push `a → ∞`.
 *
 * **The floor is the contract:** below [MIN_LABELS] raw labels or [MIN_NEGATIVE_ESS] effective negatives this
 * returns null, and the caller keeps EXACTLY the legacy 0.55 constant — a calibrator fit on a handful of points
 * would replace one hardcoded number with one fitted on noise. A non-monotone fit (a ≤ 0 — scores anti-predict
 * labels) also returns null: translating a threshold through a decreasing map would invert the section.
 */
object PlattCalibration {
    const val MIN_LABELS = 50
    const val MIN_NEGATIVE_ESS = 10.0
    private const val ITERATIONS = 25
    private const val RIDGE = 1e-6

    /** [scores] and [labels]/[weights] are parallel; label true = relevant. Null below the floor. */
    fun fit(
        scores: List<Double>,
        labels: List<Boolean>,
        weights: List<Double>,
    ): PlattMap? {
        require(scores.size == labels.size && labels.size == weights.size)
        if (scores.size < MIN_LABELS) return null
        val negativeEss = RankerEval.ess(weights.filterIndexed { i, _ -> !labels[i] })
        if (negativeEss < MIN_NEGATIVE_ESS) return null

        // Platt's smoothed targets keep the likelihood bounded on separable data.
        val positives = labels.count { it }
        val negatives = labels.size - positives
        val tPos = (positives + 1.0) / (positives + 2.0)
        val tNeg = 1.0 / (negatives + 2.0)
        val targets = labels.map { if (it) tPos else tNeg }

        var a = 1.0
        var b = 0.0
        repeat(ITERATIONS) {
            var g0 = 0.0
            var g1 = 0.0
            var h00 = RIDGE
            var h01 = 0.0
            var h11 = RIDGE
            for (i in scores.indices) {
                val s = scores[i]
                val w = weights[i]
                val p = 1.0 / (1.0 + exp(-(a * s + b)))
                val d = w * (p - targets[i])
                val v = w * p * (1 - p)
                g0 += d * s
                g1 += d
                h00 += v * s * s
                h01 += v * s
                h11 += v
            }
            val det = h00 * h11 - h01 * h01
            if (abs(det) < 1e-12) return@repeat
            val da = (g0 * h11 - g1 * h01) / det
            val db = (g1 * h00 - g0 * h01) / det
            a -= da
            b -= db
        }
        return PlattMap(a, b).takeIf { it.a > 0.0 }
    }
}
