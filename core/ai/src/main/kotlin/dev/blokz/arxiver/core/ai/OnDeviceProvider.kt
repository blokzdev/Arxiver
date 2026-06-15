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
 * Nano second). No key, no network.
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
        )

    override fun chat(request: ChatRequest): Flow<ChatChunk> =
        flow {
            val ready = engines.filter { it.isReady() }
            val preferred = preferredTier()
            val engine =
                ready.firstOrNull { it.tier == preferred }
                    ?: ready.firstOrNull()
                    ?: throw AiException(AppError.Unexpected())
            emitAll(engine.generate(request))
        }

    companion object {
        /** Gemma E2B / Nano context window (kept modest; on-device favours short prompts). */
        private const val CONTEXT_TOKENS = 4096
    }
}
