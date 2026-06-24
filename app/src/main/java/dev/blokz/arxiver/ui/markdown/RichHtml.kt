package dev.blokz.arxiver.ui.markdown

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Builds the self-contained HTML document rendered in the sandboxed offline
 * [RichBlockWebView] (Phase P-Rich R1). The model's markdown is escaped by commonmark's
 * [HtmlRenderer] (no raw HTML/script survives -- only the math we hand to KaTeX runs), then
 * `[n]` citations become `arxiver://cite/n` links. Linkification skips math spans so a
 * `\sqrt[3]{x}` is not mistaken for a citation. KaTeX renders `$..$`/`$$..$$` client-side.
 *
 * Pure (theme colors are hex strings) -> unit-testable; loads only bundled assets (offline).
 */
object RichHtml {
    private val parser: Parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()
    private val htmlRenderer: HtmlRenderer =
        HtmlRenderer.builder().extensions(listOf(TablesExtension.create())).build()

    private const val FENCE = "```"
    private val MATH_FENCE = Regex("(?s)$FENCE(?:math|latex)\\s*\\n(.*?)\\n$FENCE")
    private val MATH_SPAN = Regex("(?s)\\$\\$.*?\\$\\$|\\$[^$\\n]+\\$")
    private val CITATION = Regex("""\[(\d{1,3})]""")

    /** Full HTML doc for [markdown], themed with the given hex colors (e.g. "#1a1a1a"). */
    fun answerHtml(
        markdown: String,
        textColor: String,
        citationColor: String,
        codeBackground: String,
        mutedColor: String,
    ): String {
        // ```math fences -> $$..$$ so KaTeX's auto-render picks them up.
        val normalized = MATH_FENCE.replace(markdown) { "\$\$\n${it.groupValues[1]}\n\$\$" }
        val body = linkifyCitations(htmlRenderer.render(parser.parse(normalized)))
        return template(body, textColor, citationColor, codeBackground, mutedColor)
    }

    /** Linkify `[n]` only outside math spans, so LaTeX optional args (`[3]`) are untouched. */
    internal fun linkifyCitations(html: String): String {
        val out = StringBuilder()
        var last = 0
        for (math in MATH_SPAN.findAll(html)) {
            out.append(linkOutsideMath(html.substring(last, math.range.first)))
            out.append(math.value)
            last = math.range.last + 1
        }
        out.append(linkOutsideMath(html.substring(last)))
        return out.toString()
    }

    private fun linkOutsideMath(text: String): String =
        CITATION.replace(text) { m ->
            "<a href=\"arxiver://cite/${m.groupValues[1]}\" class=\"cite\">[${m.groupValues[1]}]</a>"
        }

    private fun template(
        body: String,
        textColor: String,
        citationColor: String,
        codeBackground: String,
        mutedColor: String,
    ): String {
        val displayDelim = "${'$'}${'$'}"
        val inlineDelim = "${'$'}"
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
            <script>
              renderMathInElement(document.body,{delimiters:[
                {left:"$displayDelim",right:"$displayDelim",display:true},
                {left:"$inlineDelim",right:"$inlineDelim",display:false}],throwOnError:false});
              function rh(){location.href='arxiver://height/'+Math.ceil(document.body.getBoundingClientRect().height);}
              window.addEventListener('load',rh);
              if(document.fonts&&document.fonts.ready){document.fonts.ready.then(rh);}
              setTimeout(rh,200);
            </script>
            </body></html>
            """.trimIndent()
    }
}

/** Whether an answer needs the rich (WebView) renderer rather than native markdown. */
object RichContent {
    private val MATH = Regex("(?s)\\$\\$.*?\\$\\$|\\$[^$\\n]+\\$|```(?:math|latex)")

    fun has(text: String): Boolean = MATH.containsMatchIn(text)
}
