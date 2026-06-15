package dev.blokz.arxiver.core.ai

import kotlinx.coroutines.flow.Flow

/**
 * A single on-device inference back-end (SPEC-AI-PROVIDERS §3). Implementations
 * wrap a concrete runtime — [GemmaEngine] (LiteRT-LM) now, a Nano engine
 * (ML Kit GenAI) in P1.2c — behind one shape so [OnDeviceProvider] can pick the
 * best ready engine without knowing the runtime.
 */
interface OnDeviceEngine {
    val tier: InferenceTier

    /** True when this engine can serve a request right now (model installed / available). */
    suspend fun isReady(): Boolean

    fun generate(request: ChatRequest): Flow<ChatChunk>
}
