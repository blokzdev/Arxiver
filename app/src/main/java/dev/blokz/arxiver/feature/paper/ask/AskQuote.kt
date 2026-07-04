package dev.blokz.arxiver.feature.paper.ask

/*
 * Quote-prefill primitives shared by the per-answer "Quote in a follow-up" action and the P-HTML
 * PH.7 reader selection→Ask path (lifted from AskSheet's file-private helper).
 */

/** Default cap for an answer re-quote; the reader's deliberate document selection gets more. */
internal const val QUOTE_MAX = 200

/** Cap for a reader selection excerpt. PROVISIONAL — device-ratified (VERIFICATION §M). */
internal const val READER_SELECTION_QUOTE_MAX = 500

/**
 * A consume-once quote offer for [AskSheet]'s `initialQuote` param. The [id] makes delivery
 * idempotent across recomposition/rotation (the same offer never re-applies over user edits) while
 * a NEW selection — even of identical [text] — gets a fresh id and applies. Deliberately not routed
 * through `AskViewModel.start()` (its idempotence guard would silently drop every offer after the
 * first of a VM lifetime).
 */
data class AskQuoteRequest(
    val id: Long,
    val text: String,
)

/**
 * Prepend a blockquote of [text] (collapsed + capped at [max]) onto the [current] input for a
 * quoted follow-up. The ellipsis compares the COLLAPSED length against the cap (the pre-lift
 * version compared the raw trimmed length, adding a spurious "…" to whitespace-heavy quotes —
 * a deliberate, golden-pinned behavior fix).
 */
internal fun quoteInto(
    text: String,
    current: String,
    max: Int = QUOTE_MAX,
): String {
    val collapsed = text.replace(Regex("\\s+"), " ").trim()
    val excerpt = collapsed.take(max)
    val ellipsis = if (collapsed.length > max) "…" else ""
    return "> $excerpt$ellipsis\n\n$current"
}
