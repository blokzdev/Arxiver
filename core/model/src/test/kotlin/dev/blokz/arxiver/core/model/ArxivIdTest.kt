package dev.blokz.arxiver.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArxivIdTest {
    @Test
    fun `parses modern id`() {
        assertEquals(ArxivId("2403.01234") to null, ArxivId.parse("2403.01234"))
    }

    @Test
    fun `parses versioned modern id`() {
        assertEquals(ArxivId("2403.01234") to 2, ArxivId.parse("2403.01234v2"))
    }

    @Test
    fun `parses legacy id`() {
        assertEquals(ArxivId("math/0211159") to null, ArxivId.parse("math/0211159"))
    }

    @Test
    fun `parses legacy id with subject class`() {
        assertEquals(ArxivId("math.GT/0309136") to 1, ArxivId.parse("math.GT/0309136v1"))
    }

    @Test
    fun `parses abs url`() {
        assertEquals(ArxivId("2403.01234") to 2, ArxivId.parse("https://arxiv.org/abs/2403.01234v2"))
    }

    @Test
    fun `parses pdf url with extension`() {
        assertEquals(ArxivId("2403.01234") to null, ArxivId.parse("https://arxiv.org/pdf/2403.01234.pdf"))
    }

    @Test
    fun `parses arXiv prefix form`() {
        assertEquals(ArxivId("2403.01234") to null, ArxivId.parse("arXiv:2403.01234"))
    }

    @Test
    fun `rejects garbage`() {
        assertNull(ArxivId.parse("not-a-paper"))
        assertNull(ArxivId.parse("https://example.com/abs/123"))
        assertNull(ArxivId.parse(""))
    }

    @Test
    fun `builds urls`() {
        val id = ArxivId("2403.01234")
        assertEquals("https://arxiv.org/abs/2403.01234", id.absUrl())
        assertEquals("https://arxiv.org/pdf/2403.01234v2", id.pdfUrl(2))
    }
}
