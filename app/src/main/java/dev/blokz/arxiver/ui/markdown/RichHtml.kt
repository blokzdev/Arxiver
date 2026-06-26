package dev.blokz.arxiver.ui.markdown

import dev.blokz.arxiver.core.ai.SvgSanitizer
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Builds the self-contained HTML document rendered in the sandboxed offline
 * [RichBlockWebView] (Phase P-Rich R1 math + R2 diagrams). The model's markdown is escaped
 * by commonmark's [HtmlRenderer] (no raw HTML/script survives — only math/diagrams we hand
 * to KaTeX/Mermaid run), then `[n]` citations become `arxiver://cite/n` links. Math spans
 * and Mermaid blocks are protected from linkification (so a `\sqrt[3]{x}` or a `B[1]` node
 * isn't mistaken for a citation). KaTeX renders `$…$`/`$$…$$`; Mermaid renders ` ```mermaid `.
 *
 * Pure (theme is hex strings + a dark flag) → unit-testable; loads only bundled assets.
 */
object RichHtml {
    private val parser: Parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()
    private val htmlRenderer: HtmlRenderer =
        HtmlRenderer.builder().extensions(listOf(TablesExtension.create())).build()

    private const val FENCE = "```"
    private val MATH_FENCE = Regex("(?s)$FENCE(?:math|latex)\\s*\\n(.*?)\\n$FENCE")
    private val MERMAID_CODE = Regex("(?s)<pre><code class=\"language-mermaid\">(.*?)</code></pre>")
    private val SVG_CODE = Regex("(?s)<pre><code class=\"language-svg\">(.*?)</code></pre>")
    private val CITATION = Regex("""\[(\d{1,3})]""")

    /** `arXiv:NNNN.NNNNN` (modern) / `arXiv:cat/NNNNNNN` (legacy) → an in-app cross-ref link. */
    private val ARXIV = Regex("""arXiv:(\d{4}\.\d{4,5}|[a-z-]+(?:\.[A-Z]{2})?/\d{7})""")

    // Spans that must NOT be touched by citation linkification: Mermaid blocks + sanitized SVG + math
    // (so a `B[1]` Mermaid node, a `<path d="…[0]…">`, or a `[3]` in LaTeX isn't mistaken for a citation).
    private val PROTECTED =
        Regex("(?s)<pre class=\"mermaid\">.*?</pre>|<div class=\"svg\">.*?</div>|\\$\\$.*?\\$\\$|\\$[^$\\n]+\\$")

    /** Full HTML doc for [markdown], themed with the given hex colors and dark flag. */
    fun answerHtml(
        markdown: String,
        textColor: String,
        citationColor: String,
        codeBackground: String,
        mutedColor: String,
        dark: Boolean = false,
        crossRefColor: String = citationColor,
    ): String {
        // ```math fences -> $$..$$ for KaTeX; ```mermaid code -> a <pre class="mermaid"> for Mermaid.
        val normalized = MATH_FENCE.replace(markdown) { "\$\$\n${it.groupValues[1]}\n\$\$" }
        val withMermaid =
            MERMAID_CODE.replace(htmlRenderer.render(parser.parse(normalized))) {
                "<pre class=\"mermaid\">${it.groupValues[1]}</pre>"
            }
        // ```svg -> a sanitized inline vector (P-Share PS.1). commonmark HTML-escaped the source, so
        // un-escape it, run the allowlist sanitizer, and inject the safe svg as raw HTML — or, when the
        // sanitizer rejects it (no svg root / nothing survives), leave the inert code block untouched.
        val rendered =
            SVG_CODE.replace(withMermaid) { m ->
                val safe = SvgSanitizer.sanitize(htmlUnescape(m.groupValues[1]))
                if (safe != null) "<div class=\"svg\">$safe</div>" else m.value
            }
        return template(linkify(rendered), textColor, citationColor, codeBackground, mutedColor, dark, crossRefColor)
    }

    /** Reverse commonmark's code-block HTML escaping so the raw `<svg>` source can be parsed + rendered. */
    private fun htmlUnescape(s: String): String =
        s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    /**
     * Linkify `[n]` citations and `arXiv:<id>` cross-references — only outside math/Mermaid spans,
     * so LaTeX `[3]` / a Mermaid `B[1]` node / an `arXiv:` inside a formula are left untouched.
     */
    internal fun linkify(html: String): String {
        val out = StringBuilder()
        var last = 0
        for (span in PROTECTED.findAll(html)) {
            out.append(linkOutside(html.substring(last, span.range.first)))
            out.append(span.value)
            last = span.range.last + 1
        }
        out.append(linkOutside(html.substring(last)))
        return out.toString()
    }

    private fun linkOutside(text: String): String {
        // arXiv cross-refs first; a legacy `cat/NNNNNNN` id encodes its slash so the WebView's
        // arxiver://paper handler reads the whole id as one path segment.
        val withXref =
            ARXIV.replace(text) { m ->
                val id = m.groupValues[1]
                "<a href=\"arxiver://paper/${id.replace("/", "%2F")}\" class=\"xref\">arXiv:$id</a>"
            }
        return CITATION.replace(withXref) { m ->
            "<a href=\"arxiver://cite/${m.groupValues[1]}\" class=\"cite\">[${m.groupValues[1]}]</a>"
        }
    }

    private fun template(
        body: String,
        textColor: String,
        citationColor: String,
        codeBackground: String,
        mutedColor: String,
        dark: Boolean,
        crossRefColor: String,
    ): String {
        val displayDelim = "${'$'}${'$'}"
        val inlineDelim = "${'$'}"
        val mermaidTheme = if (dark) "dark" else "default"
        return """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <link rel="stylesheet" href="katex.min.css">
            <style>
              html,body{margin:0;padding:0;background:transparent;color:$textColor;
                font-family:sans-serif;font-size:15px;line-height:1.5;
                -webkit-text-size-adjust:100%;overflow-wrap:break-word;word-break:break-word;}
              a.cite{color:$citationColor;text-decoration:none;font-weight:500;}
              a.xref{color:$crossRefColor;text-decoration:none;font-weight:500;}
              code{background:$codeBackground;border-radius:4px;padding:0 4px;
                font-family:monospace;font-size:0.9em;}
              pre{background:$codeBackground;border-radius:6px;padding:8px;overflow-x:auto;}
              pre code{background:transparent;padding:0;}
              pre.mermaid{background:transparent;padding:0;text-align:center;}
              pre.mermaid svg{max-width:100%;height:auto;}
              div.svg{text-align:center;margin:8px 0;}
              div.svg svg{max-width:100%;height:auto;}
              table{border-collapse:collapse;width:100%;font-size:0.9em;}
              th,td{border:1px solid $mutedColor;padding:6px;text-align:left;}
              h1,h2,h3{line-height:1.25;}
              .katex-display{overflow-x:auto;overflow-y:hidden;padding:2px 0;}
              .katex{font-size:1.05em;}
              blockquote{margin:0;padding-left:10px;border-left:3px solid $mutedColor;color:$mutedColor;}
            </style></head>
            <body>$body
            <script src="katex.min.js"></script>
            <script src="contrib/auto-render.min.js"></script>
            <script src="mermaid.min.js"></script>
            <script>
              function rh(){location.href='arxiver://height/'+Math.ceil(document.body.getBoundingClientRect().height);}
              renderMathInElement(document.body,{delimiters:[
                {left:"$displayDelim",right:"$displayDelim",display:true},
                {left:"$inlineDelim",right:"$inlineDelim",display:false}],throwOnError:false});
              if(window.mermaid){
                mermaid.initialize({startOnLoad:false,securityLevel:'strict',theme:'$mermaidTheme',
                  themeVariables:{background:'transparent',textColor:'$textColor',lineColor:'$mutedColor',
                  primaryColor:'$codeBackground',primaryTextColor:'$textColor',primaryBorderColor:'$mutedColor'}});
                mermaid.run({querySelector:'.mermaid'}).then(rh).catch(rh);
              }
              window.addEventListener('load',rh);
              if(document.fonts&&document.fonts.ready){document.fonts.ready.then(rh);}
              setTimeout(rh,300);
            </script>
            </body></html>
            """.trimIndent()
    }
}

/** Whether an answer needs the rich (WebView) renderer rather than native markdown. */
object RichContent {
    private val RICH = Regex("(?s)\\$\\$.*?\\$\\$|\\$[^$\\n]+\\$|```(?:math|latex|mermaid|svg)")

    fun has(text: String): Boolean = RICH.containsMatchIn(text)
}
