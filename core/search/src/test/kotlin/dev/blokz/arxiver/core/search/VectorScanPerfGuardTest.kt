package dev.blokz.arxiver.core.search

import dev.blokz.arxiver.core.database.dao.EmbeddingDao
import dev.blokz.arxiver.core.database.dao.NeighborRow
import dev.blokz.arxiver.core.database.dao.RelatedRow
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.database.entity.RelatedPaperEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P-Prove PP.1 — a DETERMINISTIC, flake-free CI regression tripwire on the brute-force vector scan
 * ([VectorIndex.topK]). It gates on COMPLEXITY + ALLOCATION invariants, never wall-clock (a ms ceiling on a
 * shared CI runner is either flaky or meaningless):
 *  - the scan is O(n) — exactly N dot products, never N² (catches an accidental nested loop);
 *  - the top-k result is bounded by k (the heap never grows to N);
 *  - one `FloatArray(384)` decode per row (the per-row allocation the scan is sensitive to).
 *
 * This is NOT proof of the <300ms PRD budget — that budget is device-only (BGE JNI `embedQuery` + SQLite FTS4)
 * and is measured as D2 in `VERIFICATION.md`. A future v2 sqlite-vec switch is expected to change these numbers
 * and is deliberately not gated here.
 */
class VectorScanPerfGuardTest {
    /** Serves the seeded corpus in pages; every other DAO method is out of scope for this guard. */
    private class FakeEmbeddingDao(private val corpus: List<PaperEmbeddingEntity>) : EmbeddingDao {
        override suspend fun chunk(
            limit: Int,
            offset: Int,
        ): List<PaperEmbeddingEntity> =
            if (offset >= corpus.size) emptyList() else corpus.subList(offset, minOf(offset + limit, corpus.size))

        override suspend fun count(): Int = corpus.size

        override suspend fun upsert(embedding: PaperEmbeddingEntity) = unsupported()

        override suspend fun byPaperId(paperId: String): PaperEmbeddingEntity? = unsupported()

        override fun observeCount(): Flow<Int> = unsupported()

        override suspend fun deleteAll() = unsupported()

        override suspend fun clearMarksForModelMismatch(model: String) = unsupported()

        override suspend fun deleteByModelMismatch(model: String) = unsupported()

        override suspend fun unembeddedPapers(limit: Int): List<PaperEntity> = unsupported()

        override suspend fun markEmbedded(
            paperId: String,
            embeddedAt: Long,
        ) = unsupported()

        override suspend fun clearEmbeddedMarks() = unsupported()

        override suspend fun insertRelated(related: List<RelatedPaperEntity>) = unsupported()

        override suspend fun clearRelatedFor(paperId: String) = unsupported()

        override fun observeRelated(paperId: String): Flow<List<RelatedRow>> = unsupported()

        override suspend fun neighborsFor(
            paperId: String,
            limit: Int,
        ): List<NeighborRow> = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException("perf guard uses chunk() only")
    }

    private val k = 30

    @Test
    fun `the scan is linear — exactly N dot products, never N-squared`() =
        runBlocking {
            for (n in listOf(5_000, 20_000)) {
                val calls = AtomicInteger(0)
                val index =
                    VectorIndex(
                        FakeEmbeddingDao(SeededCorpus.embeddings(n)),
                        similarity = { a, b ->
                            calls.incrementAndGet()
                            dotSimilarity(a, b)
                        },
                    )
                val hits = index.topK(SeededCorpus.queryVector(), k)
                assertEquals(n, calls.get(), "topK must call the metric once per row (O(n)); a nested loop would be N²")
                assertEquals(k, hits.size, "returns exactly the top-k, bounded heap")
            }
        }

    @Test
    fun `top-k matches a brute-force full sort — heap correctness, ordering, determinism`() =
        runBlocking {
            val corpus = SeededCorpus.embeddings(5_000)
            val q = SeededCorpus.queryVector()
            // Ground truth: score every row, full-sort, take k. The heap must reproduce this exactly.
            val expected =
                corpus
                    .map { it.paperId to dotSimilarity(q, PaperEmbeddingEntity.blobToFloats(it.vector)) }
                    .sortedByDescending { it.second }
                    .take(k)
            val actual = VectorIndex(FakeEmbeddingDao(corpus)).topK(q, k)
            assertEquals(
                expected.map { it.first },
                actual.map { it.paperId },
                "heap must return the true top-k, in order",
            )
            assertEquals(k, actual.size, "exactly k results")
            for (i in 1 until actual.size) {
                assertTrue(
                    actual[i - 1].similarity > actual[i].similarity,
                    "strictly ordered — distinct similarities, no ties",
                )
            }
        }

    @Test
    fun `per-scan allocation stays linear in N — a per-row alloc regression trips this`() =
        runBlocking {
            if (threadAllocatedBytes() == null) return@runBlocking // non-HotSpot JVM: skip, never gate
            val n = 5_000
            val index = VectorIndex(FakeEmbeddingDao(SeededCorpus.embeddings(n)))
            val before = threadAllocatedBytes()!!
            index.topK(SeededCorpus.queryVector(), k)
            val delta = threadAllocatedBytes()!! - before
            // One FloatArray(384) decode per row dominates; ceiling = N * 384 floats * 4 bytes * generous 3x headroom.
            val ceiling = n.toLong() * SeededCorpus.DIM * 4 * 3
            assertTrue(
                delta in 0..ceiling,
                "scan allocated $delta bytes, ceiling $ceiling — a per-row alloc regression?",
            )
        }

    /**
     * HotSpot's per-thread allocation counter, or null if unavailable (then the guard skips). Reached wholly by
     * reflection: `java.lang.management` is absent from the Android-lib unit-test *compile* classpath (android.jar)
     * but present at runtime on the host JVM, so `Class.forName` resolves it without a compile-time reference.
     */
    private fun threadAllocatedBytes(): Long? =
        runCatching {
            val bean =
                Class.forName(
                    "java.lang.management.ManagementFactory",
                ).getMethod("getThreadMXBean").invoke(null)!!
            val method = bean::class.java.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(bean, Thread.currentThread().id) as Long
        }.getOrNull()
}
