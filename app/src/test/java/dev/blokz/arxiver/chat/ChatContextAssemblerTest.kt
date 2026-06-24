package dev.blokz.arxiver.chat

import dev.blokz.arxiver.core.ai.ChatRole
import dev.blokz.arxiver.core.ai.ProviderCapability
import dev.blokz.arxiver.core.database.entity.ChunkEmbeddingEntity
import dev.blokz.arxiver.core.search.Provenance
import dev.blokz.arxiver.core.search.RetrievedChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatContextAssemblerTest {
    private val assembler = ChatContextAssembler(maxOutputTokens = 0, safetyMarginTokens = 0)

    private fun cap(contextTokens: Int = 100_000) =
        ProviderCapability(contextTokens = contextTokens, streaming = true, onDevice = false, requiresKey = true)

    private fun chunk(
        id: Long,
        text: String,
        score: Double,
        sourceKind: String = ChunkEmbeddingEntity.SOURCE_ABSTRACT,
    ) = RetrievedChunk(id, "2401.0000$id", text, score, Provenance.SEMANTIC, sourceKind)

    @Test
    fun `folds chunks and question into the final user turn with a system prompt`() {
        val request =
            assembler.assemble(
                question = "What is the main result?",
                chunks = listOf(chunk(1, "Transformers scale well.", 0.9)),
                history = emptyList(),
                includeNotes = true,
                capability = cap(),
            ).request

        assertTrue(request.system!!.startsWith(ChatContextAssembler.SYSTEM_PROMPT))
        val last = request.messages.last()
        assertEquals(ChatRole.USER, last.role)
        assertTrue(last.content.contains("Transformers scale well."))
        assertTrue(last.content.contains("What is the main result?"))
    }

    @Test
    fun `note chunks are dropped when notes are not included`() {
        val chunks =
            listOf(
                chunk(1, "Abstract sentence.", 0.9),
                chunk(2, "Private note text.", 0.8, ChunkEmbeddingEntity.SOURCE_NOTE),
            )

        val withNotes = assembler.assemble("q", chunks, emptyList(), includeNotes = true, capability = cap()).request
        assertTrue(withNotes.messages.last().content.contains("Private note text."))

        val without = assembler.assemble("q", chunks, emptyList(), includeNotes = false, capability = cap()).request
        assertFalse(without.messages.last().content.contains("Private note text."))
        assertTrue(without.messages.last().content.contains("Abstract sentence."))
    }

    @Test
    fun `prior turns precede the question turn`() {
        val request =
            assembler.assemble(
                question = "follow-up",
                chunks = emptyList(),
                history =
                    listOf(
                        ChatTurn(ChatRole.USER, "first question"),
                        ChatTurn(ChatRole.ASSISTANT, "first answer"),
                    ),
                includeNotes = true,
                capability = cap(),
            ).request

        assertEquals(3, request.messages.size)
        assertEquals("first question", request.messages[0].content)
        assertEquals(ChatRole.ASSISTANT, request.messages[1].role)
        assertTrue(request.messages.last().content.contains("follow-up"))
    }

    @Test
    fun `a tight context window keeps the top-scored chunk and the question`() {
        // Two equal-length (equal-cost) chunks; only the higher-scored one should fit.
        val chunks = listOf(chunk(1, "low-scored-chunk-body!", 0.1), chunk(2, "high-scored-chunk-body", 0.9))
        // Budget after system+question (~20 tokens) leaves room for one ~14-token chunk, not two.
        // cap() is a cloud capability, so the system prompt is base + the math addendum.
        val systemTokens =
            (ChatContextAssembler.SYSTEM_PROMPT.length + ChatContextAssembler.CLOUD_RICH_ADDENDUM.length + 3) / 4
        val request =
            assembler.assemble(
                "q",
                chunks,
                emptyList(),
                includeNotes = true,
                capability = cap(contextTokens = systemTokens + 21),
            ).request

        val content = request.messages.last().content
        assertTrue(content.contains("high-scored-chunk-body"), "keeps the higher-scored chunk")
        assertFalse(content.contains("low-scored-chunk-body"), "drops the lower-scored chunk")
        assertTrue(content.contains("Question: q"))
    }

    @Test
    fun `cited chunks are returned in citation order (highest score first)`() {
        // [n] order = the order chunks are labeled in the user turn = highest-scored first.
        val chunks = listOf(chunk(1, "Alpha.", 0.4), chunk(2, "Beta.", 0.9))
        val assembled = assembler.assemble("q", chunks, emptyList(), includeNotes = true, capability = cap())

        assertEquals(listOf("2401.00002", "2401.00001"), assembled.citedChunks.map { it.paperId })
        assertEquals("Beta.", assembled.citedChunks.first().text)
    }
}
