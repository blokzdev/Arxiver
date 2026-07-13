package dev.blokz.arxiver.core.ai

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlBodyTextExtractorTest {
    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/phtml/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("missing fixture /phtml/$name")

    @Test
    fun `strips math subtrees so MathML tokens never merge into prose`() {
        // The reader body keeps <math> verbatim; a naive .text() would splice "L" / "x = 2" into the prose.
        val body =
            "<p>we minimize the loss <math><mi>L</mi></math> over " +
                "<math><mi>x</mi><mo>=</mo><mn>2</mn></math> steps</p>"
        assertEquals("we minimize the loss over steps", HtmlBodyTextExtractor.extract(body))
    }

    @Test
    fun `keeps prose and figure captions`() {
        val body = "<p>Transformers use attention.</p><figcaption>Figure 1: the architecture</figcaption>"
        val text = HtmlBodyTextExtractor.extract(body)
        assertTrue("attention" in text)
        assertTrue("architecture" in text)
    }

    @Test
    fun `blank or whitespace-only input yields empty`() {
        assertEquals("", HtmlBodyTextExtractor.extract(""))
        assertEquals("", HtmlBodyTextExtractor.extract("   \n  "))
    }

    @Test
    fun `real ar5iv body extracts clean non-empty prose`() {
        val text = HtmlBodyTextExtractor.extract(fixture("ar5iv-clean-1706.03762.html"))
        assertTrue(text.length > 200, "a real paper body extracts substantial prose")
        assertTrue("attention" in text.lowercase(), "the Attention paper's prose survives extraction")
    }
}
