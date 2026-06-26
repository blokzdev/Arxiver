package dev.blokz.arxiver.feature.paper.ask

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Golden tests for the PS.2 read-aloud text extractor — rich blocks become spoken captions, never raw syntax. */
class SpeakableTextTest {
    private val labels =
        SpeakableLabels(diagram = "Diagram.", equation = "Equation.", image = "Image.", code = "Code block.")

    private fun speak(text: String) = SpeakableText.forAnswer(text, labels)

    @Test
    fun `mermaid becomes a diagram caption, not its source`() {
        val out = speak("Here is the flow:\n```mermaid\ngraph TD; A-->B\n```\nDone.")
        assertTrue(out.contains("Diagram."), out)
        assertFalse(out.contains("graph TD") || out.contains("```"), out)
    }

    @Test
    fun `display and fenced math become an equation caption`() {
        assertTrue(speak("The integral \$\$\\int_0^1 x dx\$\$ is small.").contains("Equation."))
        assertTrue(speak("```math\n\\alpha + \\beta\n```").contains("Equation."))
        assertFalse(speak("value \$\$x\$\$ end").contains("\$"))
    }

    @Test
    fun `inline math reads a single variable but captions anything complex`() {
        assertTrue(speak("the value \$x\$ here").contains("the value x here"), speak("the value \$x\$ here"))
        assertTrue(speak("set \$\\alpha + 1\$ now").contains("Equation."))
    }

    @Test
    fun `svg becomes an image caption`() {
        val out = speak("Shape:\n```svg\n<svg><circle cx=\"1\"/></svg>\n```")
        assertTrue(out.contains("Image."), out)
        assertFalse(out.contains("<svg") || out.contains("circle"), out)
    }

    @Test
    fun `other code blocks become a code caption`() {
        assertTrue(speak("```kotlin\nval x = 1\n```").contains("Code block."))
    }

    @Test
    fun `citation markers are dropped, not voiced`() {
        val out = speak("It scales [1] and is cheap [2].")
        assertFalse(Regex("""\[\d]""").containsMatchIn(out), out)
        assertTrue(out.contains("It scales") && out.contains("cheap"), out)
    }

    @Test
    fun `arXiv ids are spoken without the colon`() {
        val out = speak("Builds on arXiv:2403.01234 directly.")
        assertTrue(out.contains("arXiv 2403.01234"), out)
        assertFalse(out.contains("arXiv:"), out)
    }

    @Test
    fun `markdown syntax is stripped to plain words`() {
        val out =
            speak(
                "# Title\n**bold** and *italic* and `code` and [link](http://x).\n" +
                    "- first\n- second\n\n| a | b |\n| --- | --- |\n| 1 | 2 |",
            )
        assertFalse(out.contains("**") || out.contains("# Title") || out.contains("`code`"), out)
        assertFalse(out.contains("](http"), out)
        assertTrue(out.contains("bold") && out.contains("italic") && out.contains("link"), out)
        assertFalse(out.contains("|"), "table pipes linearized: $out")
    }

    @Test
    fun `the invariant - no machine syntax ever survives`() {
        val rich =
            "Intro \$x\$ and \$\$\\int\$\$.\n```mermaid\nA-->B\n```\nRefs [1][2].\n" +
                "| c | d |\n| - | - |\n```svg\n<svg/>\n```\nEnd arXiv:1234.56789."
        val out = speak(rich)
        assertFalse(out.contains("\$"), "no math delimiters: $out")
        assertFalse(out.contains("```"), "no code fences: $out")
        assertFalse(Regex("""\[\d]""").containsMatchIn(out), "no citation markers: $out")
        assertFalse(out.contains("|"), "no table pipes: $out")
        assertFalse(out.contains("<svg") || out.contains("A-->B"), "no rich source: $out")
    }
}
