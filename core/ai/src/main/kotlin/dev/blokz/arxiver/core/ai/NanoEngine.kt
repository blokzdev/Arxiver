package dev.blokz.arxiver.core.ai

import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Tier-1 on-device engine: system Gemini Nano via the ML Kit GenAI Prompt API
 * (`docs/reference/on-device-ai.md`). No bundled model and no slow init — the
 * OS manages Nano; we just check availability and stream. Output is capped
 * (~256 tokens, EN/KO) so this is the fallback below downloaded Gemma.
 */
class NanoEngine(
    private val availability: NanoAvailability,
    private val dispatchers: DispatcherProvider,
    private val clientProvider: () -> GenerativeModel = { Generation.getClient() },
) : OnDeviceEngine {
    override val tier: InferenceTier = InferenceTier.NANO

    override suspend fun isReady(): Boolean = availability.status() == NanoStatus.AVAILABLE

    override fun generate(request: ChatRequest): Flow<ChatChunk> =
        flow {
            val client = clientProvider()
            var emittedAny = false
            client.generateContentStream(promptOf(request)).collect { response ->
                val text = response.candidates.firstOrNull()?.text
                if (!text.isNullOrEmpty()) {
                    emittedAny = true
                    emit(ChatChunk.Delta(text))
                }
            }
            // Zero tokens is a failure, not a blank answer — surface it (see GemmaEngine).
            if (!emittedAny) throw AiException(AppError.Unexpected())
            emit(ChatChunk.Done())
        }.catch { e ->
            throw if (e is AiException) e else AiException(AppError.Unexpected(e))
        }.flowOn(dispatchers.default)

    /** Single-turn prompt (system instruction prepended); multi-turn arrives with P2. */
    private fun promptOf(request: ChatRequest): String =
        buildString {
            request.system?.let { appendLine(it).appendLine() }
            append(
                request.messages
                    .filter { it.role != ChatRole.SYSTEM }
                    .joinToString("\n\n") { it.content },
            )
        }
}
