package dev.blokz.arxiver.chat

import dev.blokz.arxiver.core.ai.ProviderCapability
import dev.blokz.arxiver.core.database.entity.ChunkEmbeddingEntity
import dev.blokz.arxiver.core.search.Provenance
import dev.blokz.arxiver.core.search.RetrievedChunk
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Redaction golden test (SPEC-AI-PROVIDERS §5): the "what leaves the device" body
 * contains exactly the intended context — and never a provider key or gated note
 * content. Mirrors `PayloadBuilderTest`.
 */
class ChatPreviewBuilderTest {
    private val assembler = ChatContextAssembler()
    private val builder = ChatPreviewBuilder()

    private fun cap() =
        ProviderCapability(
            contextTokens = 100_000,
            streaming = true,
            onDevice = false,
            requiresKey = true,
        )

    private fun chunk(
        id: Long,
        text: String,
        sourceKind: String,
    ) = RetrievedChunk(id, "2401.0000$id", text, 0.9, Provenance.SEMANTIC, sourceKind)

    @Test
    fun `preview contains the context and question but no gated note content`() {
        val chunks =
            listOf(
                chunk(1, "PUBLIC_ABSTRACT_SENTENCE", ChunkEmbeddingEntity.SOURCE_ABSTRACT),
                chunk(2, "SECRET_NOTE_SENTENCE", ChunkEmbeddingEntity.SOURCE_NOTE),
            )
        val request =
            assembler.assemble("MY_QUESTION", chunks, emptyList(), includeNotes = false, capability = cap()).request

        val preview = builder.build(request)

        assertTrue(preview.json.contains("PUBLIC_ABSTRACT_SENTENCE"))
        assertTrue(preview.json.contains("MY_QUESTION"))
        assertTrue(preview.json.contains(ChatContextAssembler.SYSTEM_PROMPT.take(20)))
        assertFalse(preview.json.contains("SECRET_NOTE_SENTENCE"), "gated note content must not leave the device")
        assertFalse(preview.text.contains("SECRET_NOTE_SENTENCE"))
    }

    @Test
    fun `included notes appear when the user opts in`() {
        val chunks = listOf(chunk(2, "OPTED_IN_NOTE", ChunkEmbeddingEntity.SOURCE_NOTE))
        val request = assembler.assemble("q", chunks, emptyList(), includeNotes = true, capability = cap()).request

        assertTrue(builder.build(request).json.contains("OPTED_IN_NOTE"))
    }
}
