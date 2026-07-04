package dev.blokz.arxiver.feature.html

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PH.7: the pure find-count reducer. Silence must never read as "0 results" (Chromium delays
 * short-query counting), "no matches" latches only once counting is DONE, and a zero-match state
 * can never render "1/0" (FindHelper clamps the ordinal at 0).
 */
class ReaderFindBarTest {
    @Test
    fun `neutral before the first callback and on a blank query`() {
        assertEquals(FindCountLabel.Neutral, reduceFindCounts("", null))
        assertEquals(FindCountLabel.Neutral, reduceFindCounts("query", null))
        assertEquals(FindCountLabel.Neutral, reduceFindCounts("", FindCounts(0, 3, true)))
    }

    @Test
    fun `no-matches latches only when counting is done`() {
        assertEquals(FindCountLabel.Neutral, reduceFindCounts("q", FindCounts(0, 0, done = false)))
        assertEquals(FindCountLabel.NoMatches, reduceFindCounts("q", FindCounts(0, 0, done = true)))
    }

    @Test
    fun `matches render one-based and live-update while counting`() {
        assertEquals(FindCountLabel.Matches(1, 5), reduceFindCounts("q", FindCounts(0, 5, done = false)))
        assertEquals(FindCountLabel.Matches(3, 17), reduceFindCounts("q", FindCounts(2, 17, done = true)))
    }
}
