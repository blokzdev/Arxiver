package dev.blokz.arxiver.feature.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PR.UX.2 — the DPI-aware render width. Pure heap-injected math, so no device needed. The contract: render 1:1
 * with the container on ample memory, but cap on a heap-derived ceiling (never a flat 2048) on low-RAM devices.
 */
class PdfTargetWidthTest {
    private val mb = 1024L * 1024L

    @Test
    fun `renders 1-1 with the container on an ample heap`() {
        assertEquals(1440, pdfTargetWidth(1440, 512 * mb))
        assertEquals(1080, pdfTargetWidth(1080, 256 * mb))
    }

    @Test
    fun `caps below the container on a low-RAM heap instead of allowing a flat 2048`() {
        val capped = pdfTargetWidth(1440, 64 * mb)
        assertTrue(capped < 1440, "expected a low-RAM cap, got $capped")
        assertTrue(capped in MIN_PDF_RENDER_WIDTH..MAX_PDF_RENDER_WIDTH, "out of bounds: $capped")
    }

    @Test
    fun `never exceeds the hard ceiling even on a huge foldable with a big heap`() {
        assertTrue(pdfTargetWidth(4000, 1024 * mb) <= MAX_PDF_RENDER_WIDTH)
    }

    @Test
    fun `never drops below the readable floor`() {
        assertEquals(MIN_PDF_RENDER_WIDTH, pdfTargetWidth(200, 256 * mb))
        // A tiny heap forces the ceiling down to the floor rather than below it.
        assertEquals(MIN_PDF_RENDER_WIDTH, pdfTargetWidth(1080, 8 * mb))
    }

    @Test
    fun `a hi-RAM device gets a strictly higher ceiling than a low-RAM one`() {
        val lo = pdfTargetWidth(4000, 64 * mb)
        val hi = pdfTargetWidth(4000, 512 * mb)
        assertTrue(hi > lo, "hi-RAM ($hi) should out-render low-RAM ($lo)")
    }
}
