package dev.blokz.arxiver.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure tests for the markdown inline builders (P-Rich R0); rendering itself is visual. */
class MarkdownTextTest {
    private val parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()
    private val palette = MdPalette(link = Color.Blue, citation = Color.Blue, codeBackground = Color.Gray)

    private fun firstBlockInline(
        markdown: String,
        onCite: ((Int) -> Unit)? = null,
    ): AnnotatedString = inlineString(requireNotNull(parser.parse(markdown).firstChild), palette, onCite)

    @Test
    fun `inline styles collapse to the underlying text`() {
        assertEquals("Hello world and code", firstBlockInline("Hello **world** and `code`").text)
    }

    @Test
    fun `a markdown link keeps its visible text`() {
        assertEquals("see arXiv", firstBlockInline("see [arXiv](https://arxiv.org)").text)
    }

    @Test
    fun `citations become clickable links only when wired`() {
        val markdown = "As shown [1] and [2]."
        assertTrue(firstBlockInline(markdown).getLinkAnnotations(0, markdown.length).isEmpty())

        val withCitations = firstBlockInline(markdown, onCite = {})
        assertEquals(2, withCitations.getLinkAnnotations(0, withCitations.length).size)
    }

    @Test
    fun `arXiv cross-refs become clickable links only when wired`() {
        val markdown = "Builds on arXiv:2403.01234 and the legacy arXiv:hep-th/9901001."
        val node = requireNotNull(parser.parse(markdown).firstChild)
        assertTrue(
            inlineString(node, palette, onCite = null).getLinkAnnotations(0, markdown.length).isEmpty(),
            "no cross-ref links until wired",
        )
        val linked = inlineString(node, palette, onCite = null, onCrossRef = {})
        assertEquals(2, linked.getLinkAnnotations(0, linked.length).size, "modern + legacy ids both linked")
    }
}
