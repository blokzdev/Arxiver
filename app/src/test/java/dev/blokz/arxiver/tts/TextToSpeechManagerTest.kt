package dev.blokz.arxiver.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure tests for the read-aloud chunker (PS.2) — long answers split under the engine's input cap, losing nothing. */
class TextToSpeechManagerTest {
    @Test
    fun `short text is a single chunk`() {
        assertEquals(listOf("hello there"), TextToSpeechManager.chunk("hello there", 100))
    }

    @Test
    fun `long text splits under the cap at sentence boundaries, losing no words`() {
        val text = "Alpha beta gamma. Delta epsilon zeta. Eta theta iota kappa lambda."
        val chunks = TextToSpeechManager.chunk(text, 22)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.isNotBlank() && it.length <= 22 }, chunks.toString())
        val joined = chunks.joinToString(" ")
        listOf(
            "Alpha",
            "epsilon",
            "kappa",
            "lambda",
        ).forEach { assertTrue(joined.contains(it), "lost '$it' in $chunks") }
    }

    @Test
    fun `a single over-cap token is hard-split with no characters lost`() {
        val chunks = TextToSpeechManager.chunk("x".repeat(50), 20)
        assertTrue(chunks.all { it.length <= 20 }, chunks.toString())
        assertEquals(50, chunks.sumOf { it.length })
    }
}
