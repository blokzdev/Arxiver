package dev.blokz.arxiver.core.search

import dev.blokz.arxiver.core.database.dao.EmbeddingDao
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity

data class VectorHit(val paperId: String, val similarity: Double)

/**
 * Brute-force cosine top-K over the BLOB embedding store (SPEC-DATA §4
 * fallback mode, adopted for v1). Vectors are L2-normalized at write time so
 * cosine reduces to a dot product; chunked reads bound memory at any corpus
 * size. ~10K papers × 384 dims scans in tens of milliseconds on-device.
 */
class VectorIndex(private val embeddingDao: EmbeddingDao) {
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
            for (row in chunk) {
                if (row.paperId == excludeId) continue
                val vector = PaperEmbeddingEntity.blobToFloats(row.vector)
                if (vector.size != query.size) continue // stale model dims — skip
                val similarity = dot(query, vector)
                if (heap.size < k) {
                    heap.add(VectorHit(row.paperId, similarity))
                } else if (heap.peek().similarity < similarity) {
                    heap.poll()
                    heap.add(VectorHit(row.paperId, similarity))
                }
            }
            offset += chunk.size
        }
        return heap.sortedByDescending { it.similarity }
    }

    private fun dot(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    companion object {
        private const val CHUNK_SIZE = 512
    }
}
