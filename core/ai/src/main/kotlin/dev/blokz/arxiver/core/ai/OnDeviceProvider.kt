package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * The on-device [AiProvider] (SPEC-AI-PROVIDERS §3). Delegates to the first
 * **ready** engine in priority order — Nano before Gemma once P1.2c adds it —
 * so the device uses the best local back-end available. No key, no network.
 */
class OnDeviceProvider(
    private val engines: List<OnDeviceEngine>,
    @Suppress("unused") private val dispatchers: DispatcherProvider,
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
            val engine = engines.firstReadyOrNull() ?: throw AiException(AppError.Unexpected())
            emitAll(engine.generate(request))
        }

    private suspend fun List<OnDeviceEngine>.firstReadyOrNull(): OnDeviceEngine? = firstOrNull { it.isReady() }

    companion object {
        /** Gemma E2B context window (kept modest; on-device favours short prompts). */
        private const val CONTEXT_TOKENS = 4096
    }
}
