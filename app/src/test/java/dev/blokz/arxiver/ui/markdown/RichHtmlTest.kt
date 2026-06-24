package dev.blokz.arxiver.ui.markdown

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure tests for the rich (KaTeX WebView) HTML builder + the rich-content gate (P-Rich R1). */
class RichHtmlTest {
    private fun html(markdown: String): String =
        RichHtml.answerHtml(
            markdown,
            textColor = "#111",
            citationColor = "#00f",
            codeBackground = "#eee",
            mutedColor = "#ccc",
        )

    @Test
    fun `wraps markdown and loads bundled katex`() {
        val out = html("Hello **world** with math \$E=mc^2\$")
        assertTrue(out.contains("katex.min.js"), "loads bundled KaTeX")
        assertTrue(out.contains("renderMathInElement"), "triggers KaTeX auto-render")
        assertTrue(out.contains("<strong>world</strong>"), "markdown is rendered to HTML")
        assertTrue(out.contains("\$E=mc^2\$"), "math delimiters survive for KaTeX")
    }

    @Test
    fun `math fences become display math`() {
        val out = html("```math\n\\int_0^1 x\\,dx\n```")
        assertTrue(out.contains("\$\$"), "```math becomes a $$ display block")
        assertTrue(out.contains("\\int_0^1"))
    }

    @Test
    fun `citations linkify outside math but not inside`() {
        val out = RichHtml.linkifyCitations("See [1] and a span ${'$'}\\sqrt[2]{x}${'$'} then [3].")
        assertTrue(out.contains("arxiver://cite/1"), "citation before math")
        assertTrue(out.contains("arxiver://cite/3"), "citation after math")
        assertTrue(out.contains("\\sqrt[2]{x}"), "the LaTeX [2] inside math is preserved")
        assertFalse(out.contains("arxiver://cite/2"), "the LaTeX optional-arg [2] is NOT a citation")
    }

    @Test
    fun `mermaid fences become renderable mermaid blocks`() {
        val out = html("```mermaid\ngraph TD; A-->B\n```")
        assertTrue(out.contains("<pre class=\"mermaid\">"), "```mermaid becomes a renderable block")
        assertTrue(out.contains("mermaid.min.js"), "loads bundled Mermaid")
        assertTrue(out.contains("mermaid.run"), "runs Mermaid")
    }

    @Test
    fun `citations skip mermaid blocks`() {
        // A Mermaid node label like B[1] must NOT be turned into a citation.
        val out =
            RichHtml.linkifyCitations("intro [1] <pre class=\"mermaid\">graph TD; A-->B[1]</pre> end [2]")
        assertTrue(out.contains("arxiver://cite/1"), "intro citation")
        assertTrue(out.contains("arxiver://cite/2"), "trailing citation")
        assertTrue(out.contains("A-->B[1]"), "the Mermaid node B[1] is preserved")
    }

    @Test
    fun `rich content gate detects math and diagrams only`() {
        assertTrue(RichContent.has("energy ${'$'}E=mc^2${'$'} here"))
        assertTrue(RichContent.has("display ${'$'}${'$'}\\int x${'$'}${'$'} block"))
        assertTrue(RichContent.has("```math\nx\n```"))
        assertTrue(RichContent.has("```mermaid\ngraph TD\n```"))
        assertFalse(RichContent.has("just **bold**, a list, and a 5 dollar price"))
    }
}
