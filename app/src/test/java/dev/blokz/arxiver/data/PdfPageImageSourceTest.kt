package dev.blokz.arxiver.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure tests for the R3d vision downscale math (the PdfRenderer path is emulator-verified). */
class PdfPageImageSourceTest {
    @Test
    fun `small pages are not upscaled`() {
        assertEquals(800 to 600, cappedSize(800, 600, maxLongEdge = 1568))
    }

    @Test
    fun `a wide page is capped on its long edge, preserving aspect`() {
        val (w, h) = cappedSize(3136, 1568, maxLongEdge = 1568)
        assertEquals(1568, w)
        assertEquals(784, h, "aspect 2:1 preserved")
    }

    @Test
    fun `a tall page is capped on its long edge (height)`() {
        val (w, h) = cappedSize(1000, 4000, maxLongEdge = 1568)
        assertEquals(1568, h)
        assertEquals(392, w, "1000 * 1568/4000")
    }

    @Test
    fun `degenerate dimensions never go below one`() {
        val (w, h) = cappedSize(0, 0, maxLongEdge = 1568)
        assertTrue(w >= 1 && h >= 1)
    }
}
