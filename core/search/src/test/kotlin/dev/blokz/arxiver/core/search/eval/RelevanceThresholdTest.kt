package dev.blokz.arxiver.core.search.eval

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The single-source "Likely relevant" cut helper (PA.1a). A fitted calibration translates p=0.5 to a raw cut;
 * anything uncalibrated keeps EXACTLY the legacy 0.55.
 */
class RelevanceThresholdTest {
    @Test
    fun `both-null calibration keeps the legacy cut exactly`() {
        assertEquals(0.55, RelevanceThreshold.cut(null, null), 0.0)
        assertEquals(RelevanceThreshold.LEGACY_CUT, RelevanceThreshold.cut(null, null), 0.0)
    }

    @Test
    fun `a half-populated calibration keeps the legacy cut`() {
        assertEquals(0.55, RelevanceThreshold.cut(10.0, null), 0.0)
        assertEquals(0.55, RelevanceThreshold.cut(null, -4.0), 0.0)
    }

    @Test
    fun `a fitted calibration translates the p=0_5 point to a raw cut`() {
        // a=10, b=-4 ⇒ scoreFor(0.5) = (ln(1) - b)/a = 4/10 = 0.4 (same fixture the ViewModel/runner tests use).
        assertEquals(0.4, RelevanceThreshold.cut(10.0, -4.0), 1e-9)
        // The helper agrees with the PlattMap inverse it delegates to.
        assertEquals(PlattMap(10.0, -4.0).scoreFor(0.5), RelevanceThreshold.cut(10.0, -4.0), 0.0)
    }

    @Test
    fun `the legacy cut is 0_55`() {
        assertEquals(0.55, RelevanceThreshold.LEGACY_CUT, 0.0)
    }
}
