package dev.blokz.arxiver.feature.html

import kotlin.test.Test
import kotlin.test.assertEquals

/** HR-FMT.5 — the system font-scale → WebView textZoom mapping, clamped to a usable band. */
class ReaderTextZoomTest {
    @Test
    fun `default scale maps to 100 percent`() {
        assertEquals(100, readerTextZoom(1.0f))
    }

    @Test
    fun `a larger accessibility scale zooms the reader text up`() {
        assertEquals(130, readerTextZoom(1.3f))
        assertEquals(115, readerTextZoom(1.15f))
    }

    @Test
    fun `extreme scales are clamped so the reader stays usable`() {
        assertEquals(300, readerTextZoom(5.0f))
        assertEquals(50, readerTextZoom(0.1f))
    }
}
