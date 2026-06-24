package dev.blokz.arxiver.feature.paper.ask

import dev.blokz.arxiver.data.Citation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure tests for the R4 Markdown export — serialization, source blocks, defensive stripping. */
class ConversationMarkdownTest {
    private val labels =
        ConversationMarkdownLabels(
            you = "You",
            assistant = "Assistant",
            sources = "Sources",
            footer = "Exported from Arxiver",
        )

    private fun assistant(
        text: String,
        citations: List<Citation> = emptyList(),
        streaming: Boolean = false,
        error: Boolean = false,
    ) = AskMessage(AskRole.ASSISTANT, text, streaming, error, citations)

    private fun user(text: String) = AskMessage(AskRole.USER, text)

    @Test
    fun `answer emits body then a sources block`() {
        val md =
            ConversationMarkdown.answer(
                assistant("The result is **strong** [1].", listOf(Citation(1, "2401.00001", "An excerpt."))),
                labels,
            )
        assertTrue(md.contains("The result is **strong** [1]."))
        assertTrue(md.contains("### Sources"))
        assertTrue(md.contains("[1] arXiv:2401.00001 — An excerpt."))
    }

    @Test
    fun `answer with no citations has no sources block`() {
        val md = ConversationMarkdown.answer(assistant("Just prose."), labels)
        assertEquals("Just prose.", md)
        assertFalse(md.contains("Sources"))
    }

    @Test
    fun `answer defensively strips a trailing followups sentinel`() {
        val md = ConversationMarkdown.answer(assistant("Body line.\nFOLLOWUPS:: a | b"), labels)
        assertFalse(md.contains("FOLLOWUPS"), "a leaked sentinel (cancelled-Max partial) must not be exported")
        assertTrue(md.contains("Body line."))
    }

    @Test
    fun `conversation serializes turns with a scope header and footer`() {
        val md =
            ConversationMarkdown.conversation(
                listOf(user("What is it?"), assistant("It is X [1].", listOf(Citation(1, "2401.1", "x")))),
                scopeLabel = "My Paper",
                labels,
            )
        assertTrue(md.startsWith("# My Paper"))
        assertTrue(md.contains("## You"))
        assertTrue(md.contains("What is it?"))
        assertTrue(md.contains("## Assistant"))
        assertTrue(md.contains("### Sources"))
        assertTrue(md.trimEnd().endsWith("— Exported from Arxiver"))
    }

    @Test
    fun `conversation skips streaming, error, and blank turns`() {
        val md =
            ConversationMarkdown.conversation(
                listOf(
                    user("Q"),
                    assistant("partial", streaming = true),
                    assistant("", error = true),
                    assistant("Real answer."),
                ),
                scopeLabel = null,
                labels,
            )
        assertFalse(md.contains("partial"), "streaming turn skipped")
        assertTrue(md.contains("Real answer."))
        assertTrue(md.startsWith("## You"), "null scope label → no H1 title, starts at the first turn")
        assertFalse(md.startsWith("# "), "no H1 scope-label line")
    }

    @Test
    fun `conversation is deterministic`() {
        val msgs = listOf(user("Q"), assistant("A"))
        assertEquals(
            ConversationMarkdown.conversation(msgs, "L", labels),
            ConversationMarkdown.conversation(msgs, "L", labels),
        )
    }

    @Test
    fun `truncateExcerpt matches the on-screen rule`() {
        assertEquals("short", truncateExcerpt("  short  "))
        val long = "x".repeat(200)
        val out = truncateExcerpt(long)
        assertEquals(161, out.length, "160 chars + ellipsis")
        assertTrue(out.endsWith("…"))
    }
}
