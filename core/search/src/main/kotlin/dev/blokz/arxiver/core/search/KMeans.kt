package dev.blokz.arxiver.core.search

import kotlin.math.min
import kotlin.random.Random

/**
 * Tiny k-means for library interest centroids (SPEC-SEARCH §5). Inputs are
 * L2-normalized embeddings; centroids are re-normalized so dot product stays a
 * valid cosine. Deterministic via fixed seed — triage scores shouldn't dance.
 */
object KMeans {
    fun centroids(
        vectors: List<FloatArray>,
        k: Int = 5,
        iterations: Int = 10,
    ): List<FloatArray> {
        if (vectors.isEmpty()) return emptyList()
        val effectiveK = min(k, vectors.size)
        val random = Random(SEED)
        var centers = vectors.shuffled(random).take(effectiveK).map { it.copyOf() }

        repeat(iterations) {
            val assignments =
                vectors.groupBy { vector ->
                    centers.indices.maxBy { dotSimilarity(vector, centers[it]) }
                }
            centers =
                centers.mapIndexed { index, old ->
                    val members = assignments[index] ?: return@mapIndexed old
                    meanVector(members).l2Normalized()
                }
        }
        return centers
    }

    /** Max cosine similarity between [vector] and any centroid. */
    fun similarityToNearest(
        vector: FloatArray,
        centroids: List<FloatArray>,
    ): Double = centroids.maxOfOrNull { dotSimilarity(vector, it) } ?: 0.0

    private const val SEED = 42L
}
