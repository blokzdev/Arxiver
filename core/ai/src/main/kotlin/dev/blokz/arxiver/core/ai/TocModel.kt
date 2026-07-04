package dev.blokz.arxiver.core.ai

/** Grouping for the reader's table of contents (P-HTML PH.6). */
enum class TocGroup { SECTIONS, FIGURES, TABLES, BIBLIOGRAPHY }

/**
 * One tappable TOC row. [depth] indents nested sections (0 = top-level `S1`, 1 = `S1.SS2`, …);
 * figures/tables/bibliography are always depth 0.
 */
data class TocEntry(
    val anchorId: String,
    val label: String,
    val group: TocGroup,
    val depth: Int,
)

/**
 * Pure TOC builder over the transform's [ReaderAnchor] list (P-HTML PH.6, SPEC-P-HTML §11) —
 * `:core:ai`, no Android imports, golden-tested against the real fixtures.
 *
 * - Sections keep document order (`extractAnchors` walks `[id]` in document order); depth is the
 *   LaTeXML id's dot-segment count − 1 (`S1` → 0, `S1.SS2` → 1).
 * - Figures/tables keep document order as flat groups.
 * - The bibliography collapses to exactly ONE entry targeting the first `bib.bibN` id — a
 *   40-reference paper must not yield 40 rows.
 */
object TocModel {
    fun buildToc(anchors: List<ReaderAnchor>): List<TocEntry> {
        val distinct = anchors.distinctBy { it.id }
        val sections =
            distinct.filter { it.type == AnchorType.SECTION }.map {
                TocEntry(it.id, it.label, TocGroup.SECTIONS, depth = it.id.count { c -> c == '.' })
            }
        val figures = distinct.filter { it.type == AnchorType.FIGURE }.map { it.toEntry(TocGroup.FIGURES) }
        val tables = distinct.filter { it.type == AnchorType.TABLE }.map { it.toEntry(TocGroup.TABLES) }
        val bibliography =
            distinct.firstOrNull { it.type == AnchorType.BIBLIOGRAPHY }
                ?.let { listOf(TocEntry(it.id, "", TocGroup.BIBLIOGRAPHY, depth = 0)) }
                ?: emptyList()
        return sections + figures + tables + bibliography
    }

    private fun ReaderAnchor.toEntry(group: TocGroup): TocEntry = TocEntry(id, label, group, depth = 0)
}
