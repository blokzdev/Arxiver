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

    // --- PRZ.1: project / unproject / visibleContentRect (the tile overlay's registration math) ---

    @Test
    fun `unproject inverts project at arbitrary scale and offset`() {
        val scale = 2.7f
        val offset = Offset(-311.5f, 842.25f)
        for (p in listOf(Offset.Zero, Offset(1000f, 2000f), Offset(123.4f, 1876.5f), Offset(999f, 1f))) {
            val roundTrip = PdfZoom.unproject(PdfZoom.project(p, scale, offset, size), scale, offset, size)
            assertEquals(p.x, roundTrip.x, eps)
            assertEquals(p.y, roundTrip.y, eps)
        }
    }

    @Test
    fun `project matches the graphicsLayer centre-origin transform`() {
        // q = C + (p − C)·s + t: the exact draw transform the overlay must mirror.
        val q = PdfZoom.project(Offset(250f, 500f), 2f, Offset(100f, -50f), size)
        assertEquals(500f + (250f - 500f) * 2f + 100f, q.x, eps)
        assertEquals(1000f + (500f - 1000f) * 2f - 50f, q.y, eps)
    }

    @Test
    fun `visibleContentRect at 1x is the whole viewport`() {
        val r = PdfZoom.visibleContentRect(1f, Offset.Zero, size)
        assertEquals(0f, r.left, eps)
        assertEquals(0f, r.top, eps)
        assertEquals(1000f, r.right, eps)
        assertEquals(2000f, r.bottom, eps)
    }

    @Test
    fun `visibleContentRect at 2x centered is the centered half viewport`() {
        val r = PdfZoom.visibleContentRect(2f, Offset.Zero, size)
        assertEquals(250f, r.left, eps)
        assertEquals(500f, r.top, eps)
        assertEquals(750f, r.right, eps)
        assertEquals(1500f, r.bottom, eps)
    }

    @Test
    fun `the visible preimage never leaves the viewport under the offset clamp`() {
        // The guarantee that a zoomed pan can only ever show COMPOSED LazyColumn items: at any clamped offset
        // the viewport's preimage stays inside the 1×-layout viewport.
        for (scale in listOf(1.2f, 2f, 3f, 4f)) {
            for (raw in listOf(Offset(1e9f, 1e9f), Offset(-1e9f, -1e9f), Offset(1e9f, -1e9f))) {
                val clamped = PdfZoom.clampOffset(raw, scale, size)
                val r = PdfZoom.visibleContentRect(scale, clamped, size)
                assertTrue(r.left >= -eps && r.top >= -eps)
                assertTrue(r.right <= size.width + eps && r.bottom <= size.height + eps)
            }
        }
    }

    @Test
    fun `focalOffset and project agree - the content point under the centroid stays put across a rescale`() {
        // Anchor consistency: take the content point under the centroid, apply focalOffset's rescale, and the
        // SAME content point must project back to the centroid (plus pan). Ties the overlay's registration math
        // to the shipped gesture math — if either drifts, tiles land off the content they sharpen.
        val oldScale = 1.6f
        val oldOffset = Offset(120f, -300f)
        val centroid = Offset(400f, 700f) // interior point: the new offset stays unclamped
        val newScale = 2.2f
        val anchor = PdfZoom.unproject(centroid, oldScale, oldOffset, size)
        val newOffset = PdfZoom.focalOffset(oldOffset, oldScale, newScale, centroid, Offset.Zero, size)
        val reprojected = PdfZoom.project(anchor, newScale, newOffset, size)
        assertEquals(centroid.x, reprojected.x, eps)
        assertEquals(centroid.y, reprojected.y, eps)
    }
}
