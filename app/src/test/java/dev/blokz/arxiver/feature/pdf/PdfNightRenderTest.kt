package dev.blokz.arxiver.feature.pdf

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PR.UX.1 — the smart-invert night transform. Pure matrix math, so no device/Robolectric needed. The point of
 * the transform is that it inverts *lightness* while preserving *hue*, unlike the plain negation it replaced.
 */
class PdfNightRenderTest {
    @Test
    fun `a white page softens to a dark grey, not a pure black`() {
        val (r, g, b) = PdfNightRender.apply(255, 255, 255)
        // Off the pure-black rail (softened) but clearly dark.
        assertTrue(r in 1..40 && g in 1..40 && b in 1..40, "white -> ($r,$g,$b)")
    }

    @Test
    fun `black ink softens to a light grey, not a blinding pure white`() {
        val (r, g, b) = PdfNightRender.apply(0, 0, 0)
        assertTrue(r in 210..252 && g in 210..252 && b in 210..252, "black -> ($r,$g,$b)")
    }

    @Test
    fun `mid grey inverts through a near-fixed mid pivot`() {
        val (r, g, b) = PdfNightRender.apply(128, 128, 128)
        assertTrue(r in 110..140 && g in 110..140 && b in 110..140, "grey -> ($r,$g,$b)")
    }

    @Test
    fun `hue family is preserved so figures stay legible`() {
        // A plain negation would send red->cyan, green->magenta, blue->yellow (dominant channel FLIPS).
        // Smart invert keeps each primary's own channel dominant.
        val red = PdfNightRender.apply(255, 0, 0)
        assertTrue(red.first >= red.second && red.first >= red.third, "red lost its family -> $red")

        val green = PdfNightRender.apply(0, 255, 0)
        assertTrue(green.second >= green.first && green.second >= green.third, "green lost its family -> $green")

        val blue = PdfNightRender.apply(0, 0, 255)
        assertTrue(blue.third >= blue.first && blue.third >= blue.second, "blue lost its family -> $blue")
    }

    @Test
    fun `every channel stays within the clamped byte range for saturated inputs`() {
        for (c in listOf(0, 64, 128, 192, 255)) {
            val (r, g, b) = PdfNightRender.apply(c, 255 - c, c)
            assertTrue(r in 0..255 && g in 0..255 && b in 0..255, "out of range for $c -> ($r,$g,$b)")
        }
    }
}
