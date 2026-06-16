package dev.blokz.arxiver.core.network.arxiv

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * UI-facing structured search, translated to arXiv's `search_query` syntax so the
 * user never types `ti:`/`cat:` by hand (replaces raw-syntax search). A scoped
 * free-text [term] (title/author/abstract/all), optional [categories] (OR-grouped),
 * and an optional submitted-date window, combined with AND. An ALL term that
 * already contains field syntax is passed through verbatim (power-user escape hatch).
 */
data class SearchFilter(
    val term: String = "",
    val field: Field = Field.ALL,
    val categories: List<String> = emptyList(),
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val sortBy: ArxivQuery.SortBy = ArxivQuery.SortBy.RELEVANCE,
    val sortOrder: ArxivQuery.SortOrder = ArxivQuery.SortOrder.DESCENDING,
) {
    enum class Field(val prefix: String) {
        ALL("all"),
        TITLE("ti"),
        AUTHOR("au"),
        ABSTRACT("abs"),
    }

    /** Nothing to search — the UI should not submit. */
    val isEmpty: Boolean get() = term.isBlank() && categories.isEmpty() && from == null && to == null

    /** Render to arXiv `search_query` (AND-joined clauses). */
    fun toSearchQuery(): String {
        val clauses = mutableListOf<String>()

        val trimmed = term.trim()
        if (trimmed.isNotEmpty()) {
            clauses +=
                if (field == Field.ALL && trimmed.containsArxivFieldSyntax()) {
                    trimmed // raw passthrough escape hatch
                } else {
                    "${field.prefix}:${trimmed.quoteIfArxivPhrase()}"
                }
        }

        if (categories.isNotEmpty()) {
            clauses += categories.joinToString(separator = " OR ", prefix = "(", postfix = ")") { "cat:$it" }
        }

        dateClause()?.let { clauses += it }

        return clauses.joinToString(" AND ")
    }

    /** `submittedDate:[YYYYMMDDHHMM TO YYYYMMDDHHMM]`; open ends are clamped to arXiv's range. */
    private fun dateClause(): String? {
        if (from == null && to == null) return null
        val lo = (from ?: ARXIV_EPOCH).atStartOfDay().format(STAMP)
        val hi = (to ?: LocalDate.now()).atTime(23, 59).format(STAMP)
        return "submittedDate:[$lo TO $hi]"
    }

    companion object {
        private val ARXIV_EPOCH = LocalDate.of(1991, 1, 1)
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
    }
}
