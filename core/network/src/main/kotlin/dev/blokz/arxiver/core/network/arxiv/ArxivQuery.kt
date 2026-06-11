package dev.blokz.arxiver.core.network.arxiv

/**
 * Structured arXiv API query, rendered to the export.arxiv.org `search_query`
 * syntax (SPEC-DATA §5). Build via [ArxivQuery.search] / [ArxivQuery.category]
 * or pass a raw query through [ArxivQuery.raw].
 */
data class ArxivQuery(
    val searchQuery: String,
    val idList: List<String> = emptyList(),
    val start: Int = 0,
    val maxResults: Int = DEFAULT_PAGE_SIZE,
    val sortBy: SortBy = SortBy.SUBMITTED_DATE,
    val sortOrder: SortOrder = SortOrder.DESCENDING,
) {
    enum class SortBy(val wire: String) {
        RELEVANCE("relevance"),
        LAST_UPDATED_DATE("lastUpdatedDate"),
        SUBMITTED_DATE("submittedDate"),
    }

    enum class SortOrder(val wire: String) {
        ASCENDING("ascending"),
        DESCENDING("descending"),
    }

    fun toQueryParameters(): Map<String, String> =
        buildMap {
            if (searchQuery.isNotBlank()) put("search_query", searchQuery)
            if (idList.isNotEmpty()) put("id_list", idList.joinToString(","))
            put("start", start.toString())
            put("max_results", maxResults.coerceIn(1, MAX_PAGE_SIZE).toString())
            put("sortBy", sortBy.wire)
            put("sortOrder", sortOrder.wire)
        }

    companion object {
        /** UI page size; well under the API's documented per-request limits. */
        const val DEFAULT_PAGE_SIZE = 25
        const val MAX_PAGE_SIZE = 100

        /** Latest papers in a category. */
        fun category(
            code: String,
            start: Int = 0,
            maxResults: Int = DEFAULT_PAGE_SIZE,
        ) = ArxivQuery(searchQuery = "cat:$code", start = start, maxResults = maxResults)

        /**
         * Free-text search. Field prefixes (`ti:`, `au:`, `abs:`, `cat:`) and
         * boolean operators are passed through verbatim; bare terms search all fields.
         */
        fun search(
            text: String,
            start: Int = 0,
            maxResults: Int = DEFAULT_PAGE_SIZE,
        ) = ArxivQuery(
            searchQuery = if (text.containsFieldSyntax()) text else "all:${text.quoteIfPhrase()}",
            start = start,
            maxResults = maxResults,
            sortBy = SortBy.RELEVANCE,
        )

        fun byIds(ids: List<String>) =
            ArxivQuery(searchQuery = "", idList = ids, maxResults = ids.size.coerceAtLeast(1))

        fun raw(
            query: String,
            start: Int = 0,
            maxResults: Int = DEFAULT_PAGE_SIZE,
        ) = ArxivQuery(searchQuery = query, start = start, maxResults = maxResults)

        private val FIELD_PREFIX = Regex("""(^|[\s(])(ti|au|abs|cat|co|jr|rn|all):""")

        private fun String.containsFieldSyntax(): Boolean =
            FIELD_PREFIX.containsMatchIn(this) || contains(" AND ") || contains(" OR ") || contains(" ANDNOT ")

        private fun String.quoteIfPhrase(): String = if (contains(' ') && !startsWith("\"")) "\"$this\"" else this
    }
}
