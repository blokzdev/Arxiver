package dev.blokz.arxiver.core.ai

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Makes a whole **untrusted** arXiv HTML-edition document (native `arxiv.org/html` or `ar5iv`) safe to
 * render in the sandboxed, JS-enabled, network-blocked reader WebView (Phase P-HTML). A full LaTeXML
 * document is a far larger attack surface than one model-emitted `<svg>`: inline `<script>`, `on*`
 * handlers (incl. on MathML), `javascript:`/`data:text/html` hrefs, external `<link>`/`<script>`/`<img>`
 * (CDN/exfil), `<iframe>`/`<object>`/`<embed>`, `<base>`/`<meta refresh>`, `<foreignObject>` inside SVG,
 * and MathML `<maction>`/`<annotation-xml encoding="text/html">`.
 *
 * Same discipline as [SvgSanitizer], via the shared [SanitizerCore]: **allowlist by parse-and-rebuild**
 * (jsoup `htmlParser`, which case-corrects foreign SVG/MathML content), keep only the HTML-body +
 * MathML allowlist, drop every `on*`, gate URI attributes (auto-loading `src` strict to local/`data:image`/
 * relative; navigational `href` kept unless a dangerous scheme — the reader transform internalises it
 * later), and delegate any embedded `<svg>` subtree to the SVG ruleset **in-tree** (no html→xml string
 * round-trip). Only `<body>` content is emitted; anything jsoup placed in `<head>` (scripts, CDN links,
 * the Typekit font sheet, base/meta) is excluded by construction.
 *
 * Returns sanitized `<body>` inner HTML, or **null** when the input is blank / too large / exceeds the
 * node budget / nothing survives — the caller (PH.3 fetcher) then degrades to the next source / PDF.
 *
 * **Defence-in-depth contract:** the sanitizer removes active threats; the reader wrapper additionally
 * carries a CSP `script-src 'none'` (blocks *execution* against jsoup↔Chromium parser-differential
 * mutation-XSS, where `blockNetworkLoads` only blocks *exfiltration*), and [sanitize] is **idempotent**
 * (`sanitize(x) == sanitize(sanitize(x))` — a regression-guarded golden). Pure & synchronous; the caller
 * runs it **off the main thread under `withTimeout`** (the node budget bounds worst-case work, but a
 * pathological document should still time out → graceful PDF fallback).
 */
object HtmlSanitizer {
    /** Whole-document byte cap (LaTeXML papers run ~0.3–1 MB; a 200 KB cap like SVG would reject most). */
    private const val MAX_INPUT = 6_000_000

    /** Whole-document element-count cap (DoS backstop on top of [SanitizerCore.MAX_DEPTH]). */
    private const val MAX_NODES = 250_000

    fun sanitize(raw: String): String? {
        if (raw.isBlank() || raw.length > MAX_INPUT) return null
        val doc =
            try {
                Jsoup.parse(raw, "", Parser.htmlParser())
            } catch (_: Exception) {
                return null
            }
        // Compact, deterministic output (no indentation churn) so re-sanitising is a stable fixed point.
        doc.outputSettings().prettyPrint(false)
        val body = doc.body()
        val budget = intArrayOf(MAX_NODES)
        for (child in body.children().toList()) {
            if (!SanitizerCore.clean(child, 0, SanitizerCore.HTML_RULESET, budget)) child.remove()
        }
        if (budget[0] <= 0) return null // tripped the node cap → treat as reject (caller degrades)
        // Trim the body boundary whitespace so re-sanitising is a stable fixed point (jsoup drops a
        // leading text node on re-parse, which would otherwise make sanitize(sanitize(x)) != sanitize(x)).
        return body.html().trim().takeIf { it.isNotBlank() }
    }
}
