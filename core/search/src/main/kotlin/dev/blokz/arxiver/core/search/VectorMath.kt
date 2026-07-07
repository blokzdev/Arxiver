package dev.blokz.arxiver.core.search

/*
 * Shared pure vector math for the on-device rankers (KMeans, RocchioRanker). One copy so the
 * centroid/normalization math can't drift between callers. Cosine reduces to a dot product because
 * every stored embedding is L2-normalized at write time — see dotSimilarity in VectorIndex.
 */

/** Elementwise mean of same-length vectors. Callers guarantee a non-empty list. */
internal fun meanVector(vectors: List<FloatArray>): FloatArray {
    val out = FloatArray(vectors.first().size)
    vectors.forEach { v -> v.forEachIndexed { i, x -> out[i] += x } }
    for (i in out.indices) out[i] /= vectors.size
    return out
}

/** L2-normalize a vector; returns it unchanged if the norm is zero (a re-normalized centroid stays a valid cosine operand). */
internal fun FloatArray.l2Normalized(): FloatArray {
    var sum = 0.0
    for (v in this) sum += v * v
    val norm = kotlin.math.sqrt(sum).toFloat()
    return if (norm == 0f) this else FloatArray(size) { this[it] / norm }
}
