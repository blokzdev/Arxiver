package dev.blokz.arxiver.core.ai

import org.jsoup.nodes.Element

/**
 * The shared **parse-and-rebuild allowlist** cleaner behind both [SvgSanitizer] (one model-emitted
 * inline `<svg>`) and [HtmlSanitizer] (a whole untrusted ar5iv/HTML document). One audited code path:
 * allowlist elements, drop **every** `on*` handler, gate URI-bearing attributes, neutralise dangerous
 * CSS. Pure JVM (jsoup) → golden-testable.
 *
 * P-HTML PH.1 extracted this from [SvgSanitizer] so the SVG attack surface stays a *single*
 * implementation even when an HTML document embeds inline `<svg>` — the HTML cleaner delegates an
 * `<svg>` subtree to the SVG [Ruleset] **in-tree** (no html→xml string round-trip, which would mangle
 * camelCase SVG and re-open mutation-XSS). Denylists are deliberately avoided (they lose to
 * case/entity/namespace tricks); we keep only what the allowlist names.
 */
internal object SanitizerCore {
    const val MAX_DEPTH = 64

    /** Dangerous in *any* attribute value or `<style>` body: js/vbscript/expression/@import/external `url(...)`. */
    val DANGEROUS_REF =
        Regex(
            """javascript:|vbscript:|expression\s*\(|@import|url\s*\(\s*['"]?\s*(?:https?:|//)""",
            RegexOption.IGNORE_CASE,
        )

    /** A scheme that must never survive on a link attribute. `data:image` is allowed; any other `data:` is not. */
    private val DANGEROUS_SCHEME =
        Regex("""^\s*(?:javascript:|vbscript:|data:(?!image/))""", RegexOption.IGNORE_CASE)

    /** Inline raster data image (the only `data:` permitted; `data:image/svg+xml` is excluded — it can carry script). */
    private val DATA_IMAGE = Regex("""^\s*data:image/(?:png|jpe?g|gif|webp);base64,""", RegexOption.IGNORE_CASE)

    /**
     * One element-class's allowlist + URI policy.
     * - [resourceAttrs]: auto-loading refs (`src`, `xlink:href`, …) — kept only as a local `#fragment`,
     *   an inline raster `data:image`, or (when [allowRelativeResource]) a relative same-origin path.
     *   External `http(s)`/protocol-relative are stripped (offline/no-CDN; they would fetch).
     * - [linkAttrs]: navigation targets (`href`) — a click target, not auto-loaded and gated by the
     *   WebView's `shouldOverrideUrlLoading`, so the *value* is kept unless it carries a dangerous
     *   scheme; the reader transform (PH.3) later internalises `#frag`/same-origin and neutralises the
     *   rest, and asserts zero external host on the final document.
     * - [nested]: a subtree ruleset switch (e.g. HTML → an embedded `<svg>` uses the SVG ruleset).
     */
    data class Ruleset(
        val allowed: Set<String>,
        val resourceAttrs: Set<String>,
        val linkAttrs: Set<String>,
        val allowRelativeResource: Boolean,
        val neutraliseDangerousStyleEl: Boolean,
        val nested: Map<String, Ruleset> = emptyMap(),
    )

    private fun isRelative(v: String): Boolean {
        val t = v.trim()
        return t.isNotEmpty() && !t.contains("://") && !t.startsWith("//") && !DANGEROUS_SCHEME.containsMatchIn(t)
    }

    private fun resourceSafe(
        v: String,
        allowRelative: Boolean,
    ): Boolean = v.trimStart().startsWith("#") || DATA_IMAGE.containsMatchIn(v) || (allowRelative && isRelative(v))

    /**
     * Allowlist [el] in place against [rs]; return false when [el] (and its subtree) must be removed.
     * [budget] is a single-cell node counter shared across the recursion — exhausting it fails the
     * element (the whole-document DoS cap; [SvgSanitizer] passes an effectively-unlimited budget).
     */
    fun clean(
        el: Element,
        depth: Int,
        rs: Ruleset,
        budget: IntArray,
    ): Boolean {
        if (depth > MAX_DEPTH || budget[0] <= 0) return false
        budget[0]--
        val name = el.normalName()
        if (name !in rs.allowed) return false
        // A subtree may switch rulesets (an HTML document's inline <svg> is cleaned with the SVG ruleset).
        val eff = rs.nested[name] ?: rs

        val drop = ArrayList<String>()
        for (attr in el.attributes()) {
            val key = attr.key.lowercase()
            val value = attr.value
            val unsafe =
                key.startsWith("on") ||
                    DANGEROUS_REF.containsMatchIn(value) ||
                    (key in eff.resourceAttrs && !resourceSafe(value, eff.allowRelativeResource)) ||
                    (key in eff.linkAttrs && DANGEROUS_SCHEME.containsMatchIn(value))
            if (unsafe) drop.add(attr.key)
        }
        drop.forEach { el.removeAttr(it) }

        // A <style> body can carry @import / external url() / expression(); strip the whole rule set
        // rather than partially clean. (HTML drops <style> entirely — it isn't allowlisted — so this
        // only fires for SVG's presentation <style>.) The XML parser keeps CSS as child text nodes.
        if (eff.neutraliseDangerousStyleEl && name == "style" &&
            DANGEROUS_REF.containsMatchIn(el.wholeText() + el.data())
        ) {
            el.empty()
        }

        for (child in el.children().toList()) {
            if (!clean(child, depth + 1, eff, budget)) child.remove()
        }
        return true
    }

    // --- Rulesets ---------------------------------------------------------------------------------

    /** SVG: shapes/paint/defs only; all URI attrs are strict resource refs (no relative — SVG has no base). */
    val SVG_RULESET =
        Ruleset(
            allowed =
                setOf(
                    "svg", "g", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon",
                    "text", "tspan", "textpath", "defs", "lineargradient", "radialgradient", "stop",
                    "clippath", "mask", "pattern", "use", "title", "desc", "style", "marker", "symbol",
                ),
            resourceAttrs = setOf("href", "xlink:href", "src", "xlink:src"),
            linkAttrs = emptySet(),
            allowRelativeResource = false,
            neutraliseDangerousStyleEl = true,
        )

    /** MathML presentation + Content (`annotation` text only). `annotation-xml`/`maction` are NOT here. */
    private val MATHML =
        setOf(
            "math", "mrow", "mi", "mo", "mn", "ms", "mtext", "mspace", "mglyph",
            "msup", "msub", "msubsup", "mfrac", "msqrt", "mroot",
            "munder", "mover", "munderover", "mmultiscripts", "mprescripts",
            "mtable", "mtr", "mlabeledtr", "mtd",
            "mpadded", "mphantom", "mstyle", "menclose", "merror", "mfenced",
            "semantics", "annotation",
        )

    /**
     * HTML body content + MathML. Deliberately **excludes** `html/head/body/meta/title/base/link/style/
     * script/iframe/object/embed/applet/form/input/button/foreignObject` and MathML `maction/
     * annotation-xml` — anything not here is dropped with its subtree. An embedded `<svg>` switches to
     * [SVG_RULESET]. `class`/`id`/`alt`/`title` are kept (harmless — only the app's bundled CSS applies,
     * and the reader wrapper's CSP blocks script).
     */
    val HTML_RULESET =
        Ruleset(
            allowed =
                setOf(
                    "article", "section", "aside", "nav", "header", "footer", "main", "div", "span", "p",
                    "h1", "h2", "h3", "h4", "h5", "h6",
                    "ul", "ol", "li", "dl", "dt", "dd", "blockquote", "pre", "code", "samp", "kbd", "var",
                    "a", "img", "figure", "figcaption", "picture", "source",
                    "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "col", "colgroup",
                    "em", "strong", "b", "i", "u", "s", "sub", "sup", "small", "mark", "del", "ins",
                    "cite", "abbr", "dfn", "q", "time", "ruby", "rt", "rp", "bdi", "bdo", "wbr", "br", "hr",
                    "svg",
                ) + MATHML,
            resourceAttrs = setOf("src", "xlink:href", "srcset", "poster", "data"),
            linkAttrs = setOf("href"),
            allowRelativeResource = true,
            neutraliseDangerousStyleEl = false,
            nested = mapOf("svg" to SVG_RULESET),
        )
}
