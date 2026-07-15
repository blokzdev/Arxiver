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
 * One same-origin figure the transform kept (PH.5): its absolute [fetchUrl] (the only place that URL
 * ever lives — never in [ReaderDocument.bodyHtml], which carries an opaque `data-img-key` token) and a
 * stable [localKey] tying the manifest → fetch result → inliner. Pure data; an **intra-download
 * handoff** from [HtmlReaderTransform] to the image fetcher + [HtmlImageInliner] — never persisted.
 */
data class ReaderImage(
    val localKey: String,
    val fetchUrl: String,
)

/** A fetched raster image ready to inline as a `data:image/<mimeSubtype>;base64,<base64>` URI (PH.5). */
data class InlinedImage(
    val mimeSubtype: String,
    val base64: String,
)

/**
 * The transformed reader body (citations/anchors internalized to `arxiver://`, `<math>` kept verbatim,
 * zero external host) + its fidelity + anchors + source. [ReaderDocWriter] wraps [bodyHtml] into the
 * full self-contained document; the caller persists [bodyHtml] (sanitize + transform happen once, on
 * download).
 *
 * PH.5: figures are kept as `<img data-img-key=…>` tokens (no `src`) and listed in [images] — an
 * **intra-download manifest** the ViewModel feeds to the image fetcher + [HtmlImageInliner], which
 * rewrites each token to a `data:image` URI (or a figcaption placeholder on miss). [images] is **never
 * persisted** and is empty on the cache-hit path (the stored body already carries the final `data:` bytes).
 */
data class ReaderDocument(
    val bodyHtml: String,
    val fidelity: FidelityReport,
    val anchors: List<ReaderAnchor>,
    val source: HtmlSource,
    val images: List<ReaderImage> = emptyList(),
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
    /** Low-contrast hue for BORDERS/rules (outlineVariant) — never body text (HR-FMT.3). */
    val muted: String,
    /** Readable muted TEXT (onSurfaceVariant) for captions/blockquotes — split from [muted] so it clears WCAG AA. */
    val mutedText: String,
    val codeBackground: String,
)

/**
 * A per-paper-version reading position (P-HTML PH.6). Payload = the nearest anchor at/above the
 * viewport top + the CSS-px offset past it — robust to the PH.5 phase-2 figure inflation, font-scale
 * changes, and theme swaps, where any raw scroll ratio lands in the wrong paragraph. [fraction] is the
 * document-scroll fraction floor used only when no anchor stands (anchor-less papers).
 *
 * Lives ONLY in the `.position` sidecar beside `index.html` ([HtmlStorage]) — never in the importable
 * backup (`ArxiverBackup`), mirroring the rendered-HTML backup wall (SPEC-P-HTML §10).
 */
data class ReaderPosition(
    val anchorId: String?,
    val offsetCssPx: Int,
    val fraction: Float,
)
