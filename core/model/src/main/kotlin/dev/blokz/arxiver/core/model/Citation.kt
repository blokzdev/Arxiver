package dev.blokz.arxiver.core.model

import java.time.ZoneOffset

/**
 * Pure, on-device citation rendering for a single [Paper] (P-Share PS.5).
 *
 * Token-free, no network, no Android deps — every field a citation needs is already local, so this
 * is a deterministic string transform the user copies/shares via the OS clipboard / share sheet
 * (never an upload, the AI key is never involved). Sibling of the bulk library BibTeX export
 * (`LibraryExporter.toBibtex`, SPEC-DATA §6), but operates on the rich [Paper] model for a single
 * focused paper rather than the export DTO.
 *
 * Scope note: this renders the **focused paper**, whose metadata is complete on-device. A paper's
 * *cited sources* are stored only as Semantic-Scholar **stubs** (title + arXiv id; no authors, year,
 * or DOI — SPEC-DATA §2 `citation_edges`), so a researcher-grade reference for them is not derivable
 * here; cited-source export is deferred until the citation sync enriches author/year metadata.
 */
object Citation {
    /**
     * A BibTeX `@misc` entry in arXiv's recommended form (`eprint` + `archivePrefix` + `primaryClass`).
     * Authors are kept in full (BibTeX consumers shorten as needed); braces in free-text fields are
     * escaped so the entry parses.
     */
    fun bibtex(paper: Paper): String {
        val year = paper.publishedAt.atZone(ZoneOffset.UTC).year
        val key = citeKey(paper, year)
        return buildString {
            appendLine("@misc{$key,")
            appendLine("  title = {${paper.title.escapeBibtex()}},")
            appendLine("  author = {${paper.authors.joinToString(" and ").escapeBibtex()}},")
            appendLine("  year = {$year},")
            appendLine("  eprint = {${paper.id.value}},")
            appendLine("  archivePrefix = {arXiv},")
            appendLine("  primaryClass = {${paper.primaryCategory}},")
            paper.journalRef?.let { appendLine("  journal = {${it.escapeBibtex()}},") }
            paper.doi?.let { appendLine("  doi = {$it},") }
            appendLine("  url = {${paper.id.absUrl()}}")
            append("}")
        }
    }

    /**
     * A human-readable one-line reference: `Authors (Year). Title. arXiv:id [primaryClass]. <link>`
     * — `et al.` past three authors, the DOI link when published, else the arXiv abstract URL.
     */
    fun reference(paper: Paper): String {
        val year = paper.publishedAt.atZone(ZoneOffset.UTC).year
        val link = paper.doi?.let { "https://doi.org/$it" } ?: paper.id.absUrl()
        return buildString {
            formatAuthors(paper.authors).takeIf { it.isNotEmpty() }?.let { append("$it ") }
            append("($year). ")
            append("${paper.title.trimEnd('.', ' ')}. ")
            append("arXiv:${paper.id.value} [${paper.primaryCategory}]. ")
            append(link)
        }
    }

    /** `lastname` + year + last 4 id digits, e.g. `vaswani20176762` — stable, lowercase, letters-only surname. */
    private fun citeKey(
        paper: Paper,
        year: Int,
    ): String {
        val surnameKey =
            paper.authors.firstOrNull()
                ?.substringAfterLast(' ')
                ?.lowercase()
                ?.filter { it.isLetter() }
                .orEmpty()
        val idDigits = paper.id.value.takeLast(4).filter { it.isDigit() }
        return "$surnameKey$year$idDigits"
    }

    /** Display names as-is (no name-part parsing); collapse to `First et al.` once past three. */
    private fun formatAuthors(authors: List<String>): String =
        when {
            authors.isEmpty() -> ""
            authors.size <= 3 -> authors.joinToString(", ")
            else -> "${authors.first()} et al."
        }

    private fun String.escapeBibtex(): String = replace("{", "\\{").replace("}", "\\}")
}
