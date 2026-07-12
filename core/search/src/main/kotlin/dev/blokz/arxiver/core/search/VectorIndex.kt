package dev.blokz.arxiver.core.search

import androidx.tracing.trace
import dev.blokz.arxiver.core.database.dao.EmbeddingDao
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity

data class VectorHit(val paperId: String, val similarity: Double)

/**
 * Brute-force cosine top-K over the BLOB embedding store (SPEC-DATA §4
 * fallback mode, adopted for v1). Vectors are L2-normalized at write time so
 * cosine reduces to a dot product; chunked reads bound memory at any corpus
 * size. ~10K papers × 384 dims scans in tens of milliseconds on-device.
 */
class VectorIndex(
    private val embeddingDao: EmbeddingDao,
    /**
     * The similarity metric; defaults to the cosine-via-dot [dotSimilarity]. Injectable ONLY so a perf-guard
     * test can count invocations (P-Prove PP.1) — production never overrides it. Mirrors `RankerEval`'s
     * injected cost-counter seam.
     */
    private val similarity: (FloatArray, FloatArray) -> Double = ::dotSimilarity,
) {
    suspend fun topK(
        query: FloatArray,
        k: Int,
        excludeId: String? = null,
    ): List<VectorHit> {
        val heap = java.util.PriorityQueue<VectorHit>(compareBy { it.similarity })
        var offset = 0
        while (true) {
            val chunk = embeddingDao.chunk(limit = CHUNK_SIZE, offset = offset)
            if (chunk.isEmpty()) break
            // Sync slice around the pure scoring loop ONLY — never the enclosing while or the suspend chunk() above
            // (a sync section spanning a suspension would begin/end on different threads → unbalanced). Fires once
            // per chunk, so the PP.3b benchmark reads it with Mode.Sum.
            trace(SearchTrace.VECTOR_TOPK_SCAN) {
                for (row in chunk) {
                    if (row.paperId == excludeId) continue
                    val vector = PaperEmbeddingEntity.blobToFloats(row.vector)
                    if (vector.size != query.size) continue // stale model dims — skip
                    val sim = similarity(query, vector)
                    if (heap.size < k) {
                        heap.add(VectorHit(row.paperId, sim))
                    } else if (heap.peek()!!.similarity < sim) {
                        heap.poll()
                        heap.add(VectorHit(row.paperId, sim))
                    }
                }
            }
            offset += chunk.size
        }
        return heap.sortedByDescending { it.similarity }
    }

    companion object {
        private const val CHUNK_SIZE = 512
    }
}

/** Dot product = cosine for vectors L2-normalized at write time (SPEC-DATA §4). */
fun dotSimilarity(
    a: FloatArray,
    b: FloatArray,
): Double {
    var sum = 0.0
    for (i in a.indices) sum += a[i] * b[i]
    return sum
}
