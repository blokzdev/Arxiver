package dev.blokz.arxiver.core.network.arxiv

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchFilterTest {
    @Test
    fun `scoped single term uses the field prefix, phrases are quoted`() {
        assertEquals("all:attention", SearchFilter(term = "attention").toSearchQuery())
        assertEquals("ti:attention", SearchFilter(term = "attention", field = SearchFilter.Field.TITLE).toSearchQuery())
        assertEquals(
            "au:\"Yann LeCun\"",
            SearchFilter(term = "Yann LeCun", field = SearchFilter.Field.AUTHOR).toSearchQuery(),
        )
    }

    @Test
    fun `categories are an OR group, AND-joined with the term`() {
        assertEquals(
            "(cat:cs.LG OR cat:cs.AI)",
            SearchFilter(categories = listOf("cs.LG", "cs.AI")).toSearchQuery(),
        )
        assertEquals(
            "ti:transformer AND (cat:cs.LG OR cat:cs.AI)",
            SearchFilter(term = "transformer", field = SearchFilter.Field.TITLE, categories = listOf("cs.LG", "cs.AI"))
                .toSearchQuery(),
        )
    }

    @Test
    fun `a date window renders a submittedDate range`() {
        val q =
            SearchFilter(
                term = "x",
                from = LocalDate.of(2024, 1, 1),
                to = LocalDate.of(2024, 1, 31),
            ).toSearchQuery()
        assertEquals("all:x AND submittedDate:[202401010000 TO 202401312359]", q)
    }

    @Test
    fun `an ALL term with field syntax passes through verbatim (escape hatch)`() {
        assertEquals(
            "ti:attention AND cat:cs.LG",
            SearchFilter(term = "ti:attention AND cat:cs.LG").toSearchQuery(),
        )
    }

    @Test
    fun `empty filter is flagged and renders nothing`() {
        assertTrue(SearchFilter().isEmpty)
        assertEquals("", SearchFilter().toSearchQuery())
        assertFalse(SearchFilter(term = "x").isEmpty)
    }

    @Test
    fun `fromFilter carries the query and the sort`() {
        val filter =
            SearchFilter(
                term = "x",
                sortBy = ArxivQuery.SortBy.SUBMITTED_DATE,
                sortOrder = ArxivQuery.SortOrder.ASCENDING,
            )
        val query = ArxivQuery.fromFilter(filter, start = 25)
        val params = query.toQueryParameters()
        assertEquals("all:x", params["search_query"])
        assertEquals("submittedDate", params["sortBy"])
        assertEquals("ascending", params["sortOrder"])
        assertEquals("25", params["start"])
    }
}
