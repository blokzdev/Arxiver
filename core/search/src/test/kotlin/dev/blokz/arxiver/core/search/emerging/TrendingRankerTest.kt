package dev.blokz.arxiver.core.search.emerging

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure emergence engine (P-Discover2 PD.3). Deterministic given a fixed clock, so every honesty guard is pinned
 * by construction — the conference-deadline self-cancel, the warmup gate, the author-diversity floor, the relative
 * frame, and the volume floors.
 */
class TrendingRankerTest {
    private val now = Instant.ofEpochMilli(1_700_000_000_000L)
    private var seq = 0

    private fun daysAgo(n: Long) = now.toEpochMilli() - n * 86_400_000L

    /** [authors] distinct author names cycled across [count] papers in [category], all published [agoDays] days ago. */
    private fun batch(
        category: String,
        count: Int,
        agoDays: Long,
        authors: Int,
        followId: Long = 1L,
    ) = (0 until count).map { i ->
        TrendingWindowPaper(
            paperId = "p${seq++}",
            categories = listOf(category),
            publishedAt = daysAgo(agoDays),
            authorsLine = "Author${i % authors}",
            followId = followId,
            followCreatedAt = daysAgo(200),
        )
    }

    /** A warm follow (30 baseline papers, 3 categories) with a genuine cs.RO surge in the recent window. */
    private fun emergingScenario() =
        batch("cs.LG", 12, 40, 12) + batch("cs.CV", 12, 40, 12) + batch("cs.RO", 6, 40, 6) +
            batch("cs.RO", 6, 3, 4) + batch("cs.LG", 2, 3, 2) + batch("cs.CV", 2, 3, 2)

    @Test
    fun `a genuine single-category surge emerges`() {
        val areas = TrendingRanker().rank(emergingScenario(), now)
        assertEquals(listOf("cs.RO"), areas.map { it.category })
        val ro = areas.single()
        assertTrue(ro.lift >= 1.5, "lift ${ro.lift}")
        assertEquals(6, ro.recentDocs)
        assertTrue(ro.drivingPaperIds.size <= 3, "driving papers capped")
    }

    @Test
    fun `a uniform global lift self-cancels to empty (the conference-deadline confound)`() {
        // Every category's recent rate equals its baseline rate ⇒ none stands out relative to the others.
        val papers =
            batch("cs.LG", 10, 40, 10) + batch("cs.CV", 10, 40, 10) + batch("cs.RO", 10, 40, 10) +
                batch("cs.LG", 5, 3, 5) + batch("cs.CV", 5, 3, 5) + batch("cs.RO", 5, 3, 5)
        assertTrue(TrendingRanker().rank(papers, now).isEmpty())
    }

    @Test
    fun `a cold first-sync follow cannot mint a trend (warmup gate)`() {
        val papers =
            batch("cs.LG", 12, 40, 12) + batch("cs.CV", 12, 40, 12) + batch("cs.RO", 6, 40, 6) +
                batch("cs.RO", 6, 3, 4) +
                batch("cs.AI", 8, 3, 4, followId = 2L) // follow 2: recent-only, no baseline ⇒ cold
        val areas = TrendingRanker().rank(papers, now)
        assertTrue("cs.AI" !in areas.map { it.category }, "a follow with no baseline can't emerge")
    }

    @Test
    fun `a single-author surge is excluded (author-diversity floor)`() {
        val papers =
            batch("cs.LG", 12, 40, 12) + batch("cs.CV", 12, 40, 12) + batch("cs.CR", 6, 40, 6) +
                batch("cs.CR", 8, 3, 1) // volume + baseline + lift, but ONE author
        assertTrue("cs.CR" !in TrendingRanker().rank(papers, now).map { it.category })
    }

    @Test
    fun `fewer than three followed categories yields nothing (no relative frame)`() {
        val papers =
            batch("cs.LG", 18, 40, 12) + batch("cs.CV", 18, 40, 12) + // only 2 categories
                batch("cs.LG", 6, 3, 4)
        assertTrue(TrendingRanker().rank(papers, now).isEmpty())
    }

    @Test
    fun `too small a baseline yields nothing`() {
        val papers =
            batch("cs.LG", 4, 40, 4) + batch("cs.CV", 4, 40, 4) + batch("cs.RO", 4, 40, 4) + // 12 baseline < 30
                batch("cs.RO", 6, 3, 4)
        assertTrue(TrendingRanker().rank(papers, now).isEmpty())
    }

    @Test
    fun `an empty corpus yields nothing`() {
        assertTrue(TrendingRanker().rank(emptyList(), now).isEmpty())
    }

    @Test
    fun `hysteresis marks an area confirmed only when it persisted`() {
        assertFalse(TrendingRanker().rank(emergingScenario(), now).single().confirmed)
        assertTrue(
            TrendingRanker().rank(emergingScenario(), now, previousDayKeys = setOf("cs.RO")).single().confirmed,
        )
    }

    @Test
    fun `output is deterministic`() {
        val papers = emergingScenario()
        assertEquals(TrendingRanker().rank(papers, now), TrendingRanker().rank(papers, now))
    }
}
