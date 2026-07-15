package dev.blokz.arxiver.feature.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PR.UX.3 — the pure focal-zoom geometry. No device needed; the gesture wiring's feel is verified on hardware.
 * The load-bearing property is "coordinate-preserving": the content point under the pinch centroid stays put.
 */
class PdfZoomTest {
    private val size = IntSize(1000, 2000)
    private val eps = 0.01f

    @Test
    fun `zooming about the centre keeps the offset at zero`() {
        val o = PdfZoom.focalOffset(Offset.Zero, 1f, 2f, Offset(500f, 1000f), Offset.Zero, size)
        assertEquals(0f, o.x, eps)
        assertEquals(0f, o.y, eps)
    }

    @Test
    fun `zooming about the top-left corner shifts the offset to keep that corner fixed`() {
        // k = 2; tx' = (0 - 500)*(1 - 2) = 500 (clamped to max 500); ty' = (0 - 1000)*(1 - 2) = 1000 (max 1000).
        val o = PdfZoom.focalOffset(Offset.Zero, 1f, 2f, Offset(0f, 0f), Offset.Zero, size)
        assertEquals(500f, o.x, eps)
        assertEquals(1000f, o.y, eps)
    }

    @Test
    fun `the offset is clamped so the scaled plane never reveals a gap`() {
        val o = PdfZoom.clampOffset(Offset(9_999f, -9_999f), 2f, size)
        assertEquals(500f, o.x, eps) // maxX = (2 - 1) * 1000 / 2
        assertEquals(-1000f, o.y, eps) // maxY = (2 - 1) * 2000 / 2
    }

    @Test
    fun `at 1x there is no room to pan`() {
        val o = PdfZoom.clampOffset(Offset(120f, -80f), 1f, size)
        assertEquals(0f, o.x, eps)
        assertEquals(0f, o.y, eps)
    }

    @Test
    fun `scale is coerced into the 1 to 4 band`() {
        assertEquals(1f, PdfZoom.coerceScale(0.3f), eps)
        assertEquals(4f, PdfZoom.coerceScale(10f), eps)
        assertEquals(2.5f, PdfZoom.coerceScale(2.5f), eps)
    }

    @Test
    fun `isZoomed ignores float dust at 1x but flags a real zoom`() {
        assertFalse(PdfZoom.isZoomed(1f))
        assertFalse(PdfZoom.isZoomed(1.0005f))
        assertTrue(PdfZoom.isZoomed(1.5f))
    }
}
