package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * The on-device [AiProvider] (SPEC-AI-PROVIDERS §3). Among the engines that are
 * **ready**, it uses the user's [preferredTier] if that engine is ready,
 * otherwise the first in default priority order (DI registers them Gemma-first,
 * Nano second). No key, no network. [capability]'s `richness` is a PLAIN placeholder —
 * the real per-turn richness comes from [resolveRichness] (the wrapped engines differ;
 * P-Atlas PA.2), which `ChatRepository.prepare` reads to shape the system prompt.
 */
class OnDeviceProvider(
    private val engines: List<OnDeviceEngine>,
    @Suppress("unused") private val dispatchers: DispatcherProvider,
    private val preferredTier: suspend () -> InferenceTier? = { null },
) : AiProvider {
    override val id: ProviderId = ProviderId.ON_DEVICE

    override val capability: ProviderCapability =
        ProviderCapability(
            contextTokens = CONTEXT_TOKENS,
            streaming = true,
            onDevice = true,
            requiresKey = false,
            // Placeholder — the real per-turn value comes from resolveRichness().
            richness = OutputRichness.PLAIN,
        )

    override fun chat(request: ChatRequest): Flow<ChatChunk> =
        flow {
            val engine = pickReadyEngine() ?: throw AiException(AppError.Unexpected())
            emitAll(engine.generate(request))
        }

    /**
     * The output richness of the engine that would serve a turn right now (P-Atlas PA.2). Read at
     * prepare time so the assembler's system prompt matches the streaming engine; a microscopic
     * prepare-vs-stream readiness flip is harmless — STRUCTURED only adds a table nudge over PLAIN,
     * and the base prompt already invites tables for either. PLAIN when no engine is ready.
     */
    suspend fun resolveRichness(): OutputRichness = pickReadyEngine()?.richness ?: OutputRichness.PLAIN

    /** The best ready engine: [preferredTier] if ready, else the first ready (DI order), else null. */
    private suspend fun pickReadyEngine(): OnDeviceEngine? {
        val ready = engines.filter { it.isReady() }
        val preferred = preferredTier()
        return ready.firstOrNull { it.tier == preferred } ?: ready.firstOrNull()
    }

    companion object {
        /** Gemma E2B / Nano context window (kept modest; on-device favours short prompts). */
        private const val CONTEXT_TOKENS = 4096
    }
}
