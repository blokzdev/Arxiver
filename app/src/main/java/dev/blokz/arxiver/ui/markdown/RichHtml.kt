package dev.blokz.arxiver.ui.markdown

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
    private val CITATION = Regex("""\[(\d{1,3})]""")

    // Spans that must NOT be touched by citation linkification: Mermaid blocks + math.
    private val PROTECTED = Regex("(?s)<pre class=\"mermaid\">.*?</pre>|\\$\\$.*?\\$\\$|\\$[^$\\n]+\\$")

    /** Full HTML doc for [markdown], themed with the given hex colors and dark flag. */
    fun answerHtml(
        markdown: String,
        textColor: String,
        citationColor: String,
        codeBackground: String,
        mutedColor: String,
        dark: Boolean = false,
    ): String {
        // ```math fences -> $$..$$ for KaTeX; ```mermaid code -> a <pre class="mermaid"> for Mermaid.
        val normalized = MATH_FENCE.replace(markdown) { "\$\$\n${it.groupValues[1]}\n\$\$" }
        val rendered =
            MERMAID_CODE.replace(htmlRenderer.render(parser.parse(normalized))) {
                "<pre class=\"mermaid\">${it.groupValues[1]}</pre>"
            }
        return template(linkifyCitations(rendered), textColor, citationColor, codeBackground, mutedColor, dark)
    }

    /** Linkify `[n]` only outside math/Mermaid spans, so LaTeX `[3]` / Mermaid `B[1]` are untouched. */
    internal fun linkifyCitations(html: String): String {
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

    private fun linkOutside(text: String): String =
        CITATION.replace(text) { m ->
            "<a href=\"arxiver://cite/${m.groupValues[1]}\" class=\"cite\">[${m.groupValues[1]}]</a>"
        }

    private fun template(
        body: String,
        textColor: String,
        citationColor: String,
        codeBackground: String,
        mutedColor: String,
        dark: Boolean,
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
              code{background:$codeBackground;border-radius:4px;padding:0 4px;
                font-family:monospace;font-size:0.9em;}
              pre{background:$codeBackground;border-radius:6px;padding:8px;overflow-x:auto;}
              pre code{background:transparent;padding:0;}
              pre.mermaid{background:transparent;padding:0;text-align:center;}
              pre.mermaid svg{max-width:100%;height:auto;}
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
    private val RICH = Regex("(?s)\\$\\$.*?\\$\\$|\\$[^$\\n]+\\$|```(?:math|latex|mermaid)")

    fun has(text: String): Boolean = RICH.containsMatchIn(text)
}
