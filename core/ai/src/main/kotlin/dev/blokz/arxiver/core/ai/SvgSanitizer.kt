package dev.blokz.arxiver.core.ai

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/**
 * Makes a model-emitted raw `<svg>` block safe to render inline in the sandboxed, JS-enabled,
 * network-blocked [RichBlockWebView] (P-Share PS.1). SVG is a real XSS vector — `<script>`,
 * `<foreignObject>` (arbitrary HTML), event handlers (`onload=…`), and external resource refs all
 * execute/fetch inside an `<svg>`. We **allowlist by parse-and-rebuild** via the shared
 * [SanitizerCore] (the same audited cleaner [HtmlSanitizer] uses): parse with jsoup's lenient XML
 * parser (preserves camelCase like `clipPath`/`linearGradient`), keep only a known-safe set of SVG
 * elements, drop every `on*` handler, restrict `href`/`xlink:href`/`src` to local fragments + inline
 * raster `data:` images, and neutralise external/`javascript:` refs in any value.
 *
 * Returns the sanitized `<svg>…</svg>` (the caller renders it inline), or **null** when there's no
 * svg root / nothing survives / it's implausibly large — the caller then falls back to an inert code
 * box (the pre-PS.1 behaviour). Pure (jsoup is a plain-JVM parser, no Android) → JVM-golden-testable,
 * mirroring [StructuredTableTransform].
 */
object SvgSanitizer {
    private const val MAX_INPUT = 200_000

    fun sanitize(raw: String): String? {
        if (raw.isBlank() || raw.length > MAX_INPUT) return null
        val doc: Document =
            try {
                Jsoup.parse(raw, "", Parser.xmlParser())
            } catch (_: Exception) {
                return null
            }
        val svg = doc.selectFirst("svg") ?: return null
        // SVG can't realistically exceed the node budget within MAX_INPUT; pass an unbounded one so
        // behaviour is identical to the pre-PH.1 standalone cleaner.
        if (!SanitizerCore.clean(svg, 0, SanitizerCore.SVG_RULESET, intArrayOf(Int.MAX_VALUE))) return null
        // Emit ONLY the svg subtree — drop any sibling/wrapper content around it.
        return svg.outerHtml().takeIf { it.isNotBlank() }
    }
}
