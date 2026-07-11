package dev.blokz.arxiver.core.search.eval

import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the P5.3 calibrator: monotone fits that place p=0.5 near the class boundary, honest nulls below the
 * floor (the caller keeps EXACTLY the legacy 0.55), the anti-predictive rejection, and the closed-form inverse.
 */
class PlattCalibrationTest {
    /** Positives score high, negatives low, with overlap — a calibratable, non-separable set. */
    private fun calibratableSet(
        n: Int,
        rng: Random = Random(11),
    ): Triple<List<Double>, List<Boolean>, List<Double>> {
        val scores = mutableListOf<Double>()
        val labels = mutableListOf<Boolean>()
        repeat(n) {
            val positive = it % 2 == 0
            val base = if (positive) 0.65 else 0.35
            scores += (base + rng.nextDouble(-0.25, 0.25)).coerceIn(0.0, 1.0)
            labels += positive
        }
        return Triple(scores, labels, List(n) { 1.0 })
    }

    @Test
    fun `fits a monotone map whose p=0_5 point sits near the class boundary`() {
        val (s, y, w) = calibratableSet(200)
        val map = assertNotNull(PlattCalibration.fit(s, y, w))
        assertTrue(map.a > 0, "monotone by contract")
        val cut = map.scoreFor(0.5)
        assertTrue(cut in 0.35..0.65, "the p=0.5 cut must sit in the overlap region, got $cut")
        // Calibration quality: high scores map to high probabilities and vice versa.
        assertTrue(map.probabilityOf(0.9) > 0.7)
        assertTrue(map.probabilityOf(0.1) < 0.3)
    }

    @Test
    fun `scoreFor is the exact inverse of probabilityOf`() {
        val (s, y, w) = calibratableSet(120)
        val map = assertNotNull(PlattCalibration.fit(s, y, w))
        listOf(0.25, 0.5, 0.75).forEach { p ->
            assertEquals(p, map.probabilityOf(map.scoreFor(p)), 1e-9)
        }
    }

    @Test
    fun `below the label floor returns null — the caller keeps the legacy constant`() {
        val (s, y, w) = calibratableSet(PlattCalibration.MIN_LABELS - 2)
        assertNull(PlattCalibration.fit(s, y, w))
    }

    @Test
    fun `a weight-starved negative class returns null — ESS, not raw rows`() {
        // 60 labels, but the 25 negatives are all weak dismisses: ESS = 25*0.3^2... = (25*.3)^2/(25*.09) = 25*0.09/0.09...
        // Kish: (7.5)^2 / 2.25 = 25 — uniform weights keep count. Mix ONE strong negative among weak ones instead:
        val scores = List(60) { if (it < 35) 0.7 else 0.3 }
        val labels = List(60) { it < 35 }
        val weights =
            List(60) { i ->
                if (labels[i]) {
                    1.0
                } else if (i == 35) {
                    1.0
                } else {
                    0.05
                }
            }
        // negative ESS = (1 + 24*0.05)^2 / (1 + 24*0.0025) = (2.2)^2/1.06 ≈ 4.6 < 10
        assertNull(PlattCalibration.fit(scores, labels, weights))
    }

    @Test
    fun `an anti-predictive score is rejected, never inverted`() {
        // Scores point the WRONG way: positives low, negatives high. A fitted a<=0 would invert the section.
        val scores = List(100) { if (it % 2 == 0) 0.2 else 0.8 }
        val labels = List(100) { it % 2 == 0 }
        assertNull(PlattCalibration.fit(scores, labels, List(100) { 1.0 }))
    }
}
