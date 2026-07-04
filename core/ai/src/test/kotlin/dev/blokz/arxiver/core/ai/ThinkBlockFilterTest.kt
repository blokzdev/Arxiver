package dev.blokz.arxiver.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Qwen3 `<think>` stream filter (PA.6 follow-up). Captured live on-device (K20): the reasoning
 * monologue rendered to the user before the real answer. The filter must strip the block across
 * arbitrary chunk boundaries and pass non-thinking replies through untouched.
 */
class ThinkBlockFilterTest {
    private fun streamed(vararg deltas: String): String {
        val filter = ThinkBlockFilter()
        return deltas.joinToString("") { filter.filter(it) }
    }

    @Test
    fun `strips a full think block arriving in one chunk`() {
        assertEquals("The answer.", streamed("<think>reasoning here</think>The answer."))
    }

    @Test
    fun `strips the empty pair the no_think switch still emits`() {
        assertEquals("The answer.", streamed("<think>\n\n</think>\n\nThe answer."))
    }

    @Test
    fun `strips a think block split across many chunks — including a split open tag`() {
        assertEquals(
            "Real answer.",
            streamed("<th", "ink>let me con", "sider the context", "</thi", "nk>\nReal answer."),
        )
    }

    @Test
    fun `passes a reply with no think block through untouched`() {
        assertEquals("Plain grounded answer [1].", streamed("Plain ", "grounded answer [1]."))
    }

    @Test
    fun `a reply that merely starts with an angle bracket is not swallowed`() {
        assertEquals("<b>bold</b> start", streamed("<b>bold</b> start"))
    }

    @Test
    fun `leading whitespace before the think block is handled`() {
        assertEquals("Answer.", streamed("  \n<think>hmm</think>Answer."))
    }

    @Test
    fun `content after the close tag in the same chunk survives`() {
        assertEquals("A. B.", streamed("<think>x</think>A.", " B."))
    }

    @Test
    fun `a thinking-only stream yields nothing (the zero-token guard then errors honestly)`() {
        assertEquals("", streamed("<think>only reasoning, no answer</think>"))
    }
}
