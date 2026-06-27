package dev.blokz.arxiver.core.ai

/*
 * The product of HtmlReaderTransform: a sanitized + internalized LaTeXML reader body plus the
 * metadata the UI (PH.4) and the future TOC (PH.6) need. Pure data, no Android. (Phase P-HTML PH.3.)
 */

/** Which arXiv HTML source served the document. */
enum class HtmlSource { NATIVE, AR5IV }

/** Conversion-fidelity verdict (SPEC-P-HTML §4). DEGRADED → the fetcher falls to the next source/PDF. */
enum class Fidelity { OK, DEGRADED }

enum class AnchorType { SECTION, FIGURE, TABLE, BIBLIOGRAPHY }

/** A navigable target (section/figure/table/bib) extracted for the PH.6 table-of-contents. */
data class ReaderAnchor(
    val id: String,
    val type: AnchorType,
    val label: String,
)

/**
 * Why a document is OK/DEGRADED, with the raw counts the thresholds (SPEC-P-HTML §4) used — so a
 * device session can retune `MISSING_DENSITY` / `MIN_CITES_FOR_BIB_CHECK` from real numbers.
 */
data class FidelityReport(
    val fidelity: Fidelity,
    val reason: String?,
    val missingCitationCount: Int,
    val resolvableBibCount: Int,
    val bibTargetingCiteCount: Int,
)

/**
 * The transformed reader body (citations/anchors internalized to `arxiver://`, images → placeholders,
 * `<math>` kept verbatim, zero external host) + its fidelity + anchors + source. [ReaderDocWriter]
 * wraps [bodyHtml] into the full self-contained document; the caller persists [bodyHtml] (sanitize +
 * transform happen once, on download).
 */
data class ReaderDocument(
    val bodyHtml: String,
    val fidelity: FidelityReport,
    val anchors: List<ReaderAnchor>,
    val source: HtmlSource,
)

/**
 * Runtime theme colors (hex `#RRGGBB`) supplied by `:app` at PH.4 from `rememberRichTheme()` and
 * injected into the reader document's CSS custom properties by [ReaderDocWriter]. Kept here (not in
 * `:app`) so the writer stays a pure, golden-testable `:core:ai` function.
 */
data class ReaderTheme(
    val text: String,
    val background: String,
    val link: String,
    val muted: String,
    val codeBackground: String,
)
