package dev.blokz.arxiver.core.search

/**
 * Two-sided Rocchio relevance for inbox triage (SPEC-SEARCH §5). The user's interest is modelled as
 * positive centroids (k-means over library saves + explicit thumbs-up) pulled *toward*, and a single
 * pooled negative centroid (dismissed + thumbs-down) pushed *away*:
 *
 *   score(v) = clamp01( α · maxCosine(v, positiveCentroids) − γ · cosine(v, negativeCentroid) )
 *
 * Output is clamped to `[0,1]` so it is a drop-in for the shipped positive-only score — the
 * `RELEVANT_THRESHOLD = 0.55` "Likely relevant" cut and the `ScoreBar` fill both keep working
 * unchanged. With no negatives (`negativeCentroid == null`) the score is *identical* to
 * [KMeans.similarityToNearest]: this is a purely additive deepen that degrades to today's behaviour.
 *
 * Pure and deterministic (the centroids it consumes come from the seeded [KMeans]); the caller owns
 * label selection, the dedupe rule (a positive paper is never also a negative), and the ≥10-vector
 * cold-start gate.
 */
object RocchioRanker {
    const val DEFAULT_ALPHA = 1.0

    /** Negative weight; deliberately < α so one dislike cluster can't overwhelm a strong positive match. */
    const val DEFAULT_GAMMA = 0.5

    /** Below this many negatives the pooled centroid is too noisy to trust — no push-away is applied. */
    const val NEGATIVE_MINIMUM = 3

    /**
     * Pooled, re-normalized negative centroid from [negatives], or null when there are fewer than
     * [minimum] (one stray dismiss shouldn't distort the whole feed). A single centroid — not k
     * clusters — because dismisses are a diffuse "not this" signal and pooling is robust at small n.
     */
    fun negativeCentroid(
        negatives: List<FloatArray>,
        minimum: Int = NEGATIVE_MINIMUM,
    ): FloatArray? {
        if (negatives.size < minimum) return null
        return meanVector(negatives).l2Normalized()
    }

    /**
     * Nearest-shrunken-centroid regularization (P5.2): pull each positive centroid toward the global save-mean
     * by [lambda], then re-normalize. Cuts the variance of noisy small-n clusters — the cold-start n≈10 band —
     * at the cost of smoothing a genuinely multi-interest profile, which is why [lambda] is **selected per-user
     * by the P5.1 harness on a pre-registered grid and confirmed on the time split**, never a shipped constant
     * (no telemetry ⇒ a hardcoded default would be calibrated on one developer's library).
     *
     * `lambda = 0` returns the SAME list instance — bit-identical to the pre-P5.2 scorer by construction.
     */
    fun shrinkCentroids(
        centroids: List<FloatArray>,
        lambda: Double,
    ): List<FloatArray> {
        if (lambda <= 0.0 || centroids.size < 2) return centroids
        val mean = meanVector(centroids)
        return centroids.map { c ->
            FloatArray(c.size) { i -> ((1 - lambda) * c[i] + lambda * mean[i]).toFloat() }.l2Normalized()
        }
    }

    /** Relevance of [vector] in `[0,1]` given positive interest centroids and an optional pooled negative. */
    fun score(
        vector: FloatArray,
        positiveCentroids: List<FloatArray>,
        negativeCentroid: FloatArray?,
        alpha: Double = DEFAULT_ALPHA,
        gamma: Double = DEFAULT_GAMMA,
    ): Double {
        val positiveSimilarity = KMeans.similarityToNearest(vector, positiveCentroids)
        val negativeSimilarity = if (negativeCentroid != null) dotSimilarity(vector, negativeCentroid) else 0.0
        return (alpha * positiveSimilarity - gamma * negativeSimilarity).coerceIn(0.0, 1.0)
    }
}
