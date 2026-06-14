package dev.blokz.arxiver.core.network.arxiv

import org.junit.Test
import kotlin.test.assertEquals

class ArxivQueryTest {
    @Test
    fun `category query renders cat prefix`() {
        val params = ArxivQuery.category("cs.LG").toQueryParameters()
        assertEquals("cat:cs.LG", params["search_query"])
        assertEquals("submittedDate", params["sortBy"])
        assertEquals("descending", params["sortOrder"])
    }

    @Test
    fun `bare text search wraps in all field`() {
        val params = ArxivQuery.search("attention").toQueryParameters()
        assertEquals("all:attention", params["search_query"])
        assertEquals("relevance", params["sortBy"])
    }

    @Test
    fun `multi-word bare search becomes quoted phrase`() {
        val params = ArxivQuery.search("state space models").toQueryParameters()
        assertEquals("all:\"state space models\"", params["search_query"])
    }

    @Test
    fun `field-prefixed search passes through verbatim`() {
        val q = "ti:attention AND cat:cs.LG"
        assertEquals(q, ArxivQuery.search(q).toQueryParameters()["search_query"])
    }

    @Test
    fun `boolean query passes through verbatim`() {
        val q = "transformer ANDNOT vision"
        assertEquals(q, ArxivQuery.search(q).toQueryParameters()["search_query"])
    }

    @Test
    fun `id list query omits search_query`() {
        val params = ArxivQuery.byIds(listOf("2403.01234", "math/0211159")).toQueryParameters()
        assertEquals("2403.01234,math/0211159", params["id_list"])
        assertEquals(null, params["search_query"])
    }

    @Test
    fun `max results is clamped to API page limit`() {
        val params = ArxivQuery.category("cs.LG", maxResults = 5000).toQueryParameters()
        assertEquals("100", params["max_results"])
    }

    @Test
    fun `max results is clamped to at least one`() {
        assertEquals("1", ArxivQuery.category("cs.LG", maxResults = 0).toQueryParameters()["max_results"])
    }

    @Test
    fun `start offset is emitted for paging`() {
        val params = ArxivQuery.category("cs.LG", start = 50, maxResults = 25).toQueryParameters()
        assertEquals("50", params["start"])
        assertEquals("25", params["max_results"])
    }

    @Test
    fun `byIds with empty list still emits a valid page size`() {
        val params = ArxivQuery.byIds(emptyList()).toQueryParameters()
        assertEquals("1", params["max_results"])
        assertEquals(null, params["id_list"])
        assertEquals(null, params["search_query"])
    }
}
