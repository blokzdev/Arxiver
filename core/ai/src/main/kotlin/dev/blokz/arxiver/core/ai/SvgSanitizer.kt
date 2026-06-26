package dev.blokz.arxiver.core.ai

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * Makes a model-emitted raw `<svg>` block safe to render inline in the sandboxed, JS-enabled,
 * network-blocked [RichBlockWebView] (P-Share PS.1). SVG is a real XSS vector — `<script>`,
 * `<foreignObject>` (arbitrary HTML), event handlers (`onload=…`), and external resource refs all
 * execute/fetch inside an `<svg>`. We **allowlist by parse-and-rebuild**, not regex-strip
 * (denylists lose to case/entity/namespace tricks): parse with jsoup's lenient XML parser, keep only
 * a known-safe set of SVG elements, drop every `on*` handler, restrict `href`/`xlink:href`/`src` to
 * local fragments + inline base64 `data:` images, and neutralise external/`javascript:` refs in any value.
 *
 * Returns the sanitized `<svg>…</svg>` (the caller renders it inline), or **null** when there's no
 * svg root / nothing survives / it's implausibly large — the caller then falls back to an inert code
 * box (the pre-PS.1 behaviour). Pure (jsoup is a plain-JVM parser, no Android) → JVM-golden-testable,
 * mirroring [StructuredTableTransform].
 */
object SvgSanitizer {
    /** Known-safe SVG elements (lowercased; matched against jsoup `normalName()`). Anything else is dropped. */
    private val ALLOWED =
        setOf(
            "svg", "g", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon",
            "text", "tspan", "textpath", "defs", "lineargradient", "radialgradient", "stop",
            "clippath", "mask", "pattern", "use", "title", "desc", "style", "marker", "symbol",
        )

    /** Attributes that name a resource — kept only as a local `#fragment` or an inline base64 data image. */
    private val URI_ATTRS = setOf("href", "xlink:href", "src", "xlink:src")

    private val SAFE_URI = Regex("""^\s*(#|data:image/(png|jpe?g|gif|webp);base64,)""", RegexOption.IGNORE_CASE)

    /** Dangerous in *any* attribute value or `<style>` body: js/expression/imports/external `url(...)`. */
    private val DANGEROUS_REF =
        Regex("""javascript:|expression\s*\(|@import|url\s*\(\s*['"]?\s*(?:https?:|//)""", RegexOption.IGNORE_CASE)

    private const val MAX_INPUT = 200_000
    private const val MAX_DEPTH = 48

    fun sanitize(raw: String): String? {
        if (raw.isBlank() || raw.length > MAX_INPUT) return null
        val doc: Document =
            try {
                Jsoup.parse(raw, "", Parser.xmlParser())
            } catch (_: Exception) {
                return null
            }
        val svg = doc.selectFirst("svg") ?: return null
        if (!clean(svg, 0)) return null
        // Emit ONLY the svg subtree — drop any sibling/wrapper content around it.
        return svg.outerHtml().takeIf { it.isNotBlank() }
    }

    /** Allowlist [el] in place; return false if [el] itself (and its subtree) must be removed. */
    private fun clean(
        el: Element,
        depth: Int,
    ): Boolean {
        if (depth > MAX_DEPTH || el.normalName() !in ALLOWED) return false

        val drop = ArrayList<String>()
        for (attr in el.attributes()) {
            val key = attr.key.lowercase()
            val value = attr.value
            val unsafe =
                key.startsWith("on") ||
                    (key in URI_ATTRS && !SAFE_URI.containsMatchIn(value)) ||
                    DANGEROUS_REF.containsMatchIn(value)
            if (unsafe) drop.add(attr.key)
        }
        drop.forEach { el.removeAttr(it) }

        // A <style> body can carry @import / external url() / expression() — strip the whole rule set
        // rather than partially clean (model SVG diagrams style via presentation attributes anyway).
        // The XML parser keeps CSS as child text nodes (not Element.data()), so check both + empty().
        if (el.normalName() == "style" && DANGEROUS_REF.containsMatchIn(el.wholeText() + el.data())) el.empty()

        // Recurse element children (text nodes are escaped on output, so they're safe to keep).
        for (child in el.children().toList()) {
            if (!clean(child, depth + 1)) child.remove()
        }
        return true
    }
}
