package dev.blokz.arxiver.chat

import dev.blokz.arxiver.core.ai.ChatMessage
import dev.blokz.arxiver.core.ai.ChatRequest
import dev.blokz.arxiver.core.ai.ChatRole
import dev.blokz.arxiver.core.ai.ProviderCapability
import dev.blokz.arxiver.core.database.entity.ChunkEmbeddingEntity
import dev.blokz.arxiver.core.search.RetrievedChunk

/** A prior chat turn, chronological, fed back as conversational context. */
data class ChatTurn(val role: ChatRole, val content: String)

/**
 * Builds a provider-neutral [ChatRequest] for a grounded answer (SPEC-AI-PROVIDERS
 * chat orchestration). Folds retrieved chunks into the final user turn as a labeled,
 * citable context block, prepends prior turns, and fits everything inside the
 * provider's context window using a tokenizer-free char/4 proxy (no chat tokenizer
 * exists; providers do their own exact counting). When the budget is tight it drops
 * the oldest history first, then the lowest-scored chunks — the question is never
 * dropped. Note-derived chunks are gated by [includeNotes], mirroring how the
 * dispatch bridge gates notes. Pure / deterministic.
 */
class ChatContextAssembler(
    private val maxOutputTokens: Int = ChatRequest.DEFAULT_MAX_TOKENS,
    private val safetyMarginTokens: Int = SAFETY_MARGIN_TOKENS,
) {
    fun assemble(
        question: String,
        chunks: List<RetrievedChunk>,
        history: List<ChatTurn>,
        includeNotes: Boolean,
        capability: ProviderCapability,
    ): ChatRequest {
        val gated = if (includeNotes) chunks else chunks.filter { it.sourceKind != ChunkEmbeddingEntity.SOURCE_NOTE }

        var budget =
            capability.contextTokens - maxOutputTokens - safetyMarginTokens -
                estTokens(SYSTEM_PROMPT) - estTokens(question)

        // Highest-scored chunks first, keep those that fit.
        val keptChunks = mutableListOf<RetrievedChunk>()
        for (chunk in gated.sortedByDescending { it.score }) {
            val cost = estTokens(chunk.text) + CHUNK_LABEL_TOKENS
            if (budget - cost < 0) continue
            budget -= cost
            keptChunks += chunk
        }

        // Most-recent history that still fits, then restore chronological order.
        val keptHistory = ArrayDeque<ChatTurn>()
        for (turn in history.asReversed()) {
            val cost = estTokens(turn.content)
            if (budget - cost < 0) break
            budget -= cost
            keptHistory.addFirst(turn)
        }

        val messages =
            keptHistory.map { ChatMessage(it.role, it.content) } +
                ChatMessage(ChatRole.USER, userTurn(question, keptChunks))

        return ChatRequest(messages = messages, system = SYSTEM_PROMPT, maxTokens = maxOutputTokens)
    }

    private fun userTurn(
        question: String,
        chunks: List<RetrievedChunk>,
    ): String =
        buildString {
            if (chunks.isNotEmpty()) {
                appendLine("Context from the user's library:")
                chunks.forEachIndexed { i, c ->
                    appendLine("[${i + 1}] (arXiv:${c.paperId}) ${c.text}")
                }
                appendLine()
            }
            append("Question: ")
            append(question)
        }

    private fun estTokens(text: String): Int = (text.length + 3) / 4

    companion object {
        /** Reserve so the char/4 estimate's slack never overruns the real window. */
        const val SAFETY_MARGIN_TOKENS = 256
        private const val CHUNK_LABEL_TOKENS = 8

        /** Model-facing system instruction (English, like the routine starter text). */
        const val SYSTEM_PROMPT =
            "You are a research assistant inside the Arxiver app. Answer the user's question " +
                "using ONLY the provided context excerpts from their library. Cite the excerpts you " +
                "use by their [number]. If the context does not contain the answer, say so plainly " +
                "rather than guessing. Be concise and precise."
    }
}
