package dev.blokz.arxiver.core.search

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HybridFusionTest {
    @Test
    fun `paper in both legs gets BOTH provenance and combined score`() {
        val hits =
            HybridFusion.fuse(
                keyword = listOf("a" to 10.0, "b" to 5.0),
                semantic = listOf("a" to 0.9, "c" to 0.8),
            )
        val a = hits.first { it.paperId == "a" }
        assertEquals(Provenance.BOTH, a.provenance)
        assertEquals("a", hits.first().paperId) // top of both legs wins overall
        assertEquals(1.0, a.score, 1e-9) // 0.25*1.0 + 0.75*1.0
    }

    @Test
    fun `semantic-only paper outranks keyword-only at equal normalized strength`() {
        val hits =
            HybridFusion.fuse(
                keyword = listOf("kw" to 10.0, "kw2" to 1.0),
                semantic = listOf("sem" to 0.9, "sem2" to 0.1),
            )
        val kw = hits.firstOrNull { it.paperId == "kw" }
        val sem = hits.first { it.paperId == "sem" }
        // 0.75 vs 0.25 weighting; the keyword-only top may even be gated out.
        assertTrue(sem.score > (kw?.score ?: 0.0))
        assertEquals(Provenance.SEMANTIC, sem.provenance)
    }

    @Test
    fun `quality gate drops weak tail`() {
        val hits =
            HybridFusion.fuse(
                keyword = emptyList(),
                semantic = listOf("strong" to 1.0, "mid" to 0.95, "weak" to 0.05),
            )
        // weak normalizes to 0.0 → gated out (< 70% of best).
        assertEquals(listOf("strong", "mid"), hits.map { it.paperId })
    }

    @Test
    fun `empty legs produce empty result`() {
        assertTrue(HybridFusion.fuse(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `keyword-only mode works when semantic leg is absent`() {
        val hits =
            HybridFusion.fuse(
                keyword = listOf("a" to 3.0, "b" to 2.9),
                semantic = emptyList(),
            )
        assertEquals("a", hits.first().paperId)
        assertTrue(hits.all { it.provenance == Provenance.KEYWORD })
    }

    @Test
    fun `result limit respected`() {
        val keyword = (1..50).map { "p$it" to (100.0 - it) }
        val hits = HybridFusion.fuse(keyword, emptyList(), SearchTuning(qualityGate = 0.0))
        assertTrue(hits.size <= SearchTuning().resultLimit)
    }
}
