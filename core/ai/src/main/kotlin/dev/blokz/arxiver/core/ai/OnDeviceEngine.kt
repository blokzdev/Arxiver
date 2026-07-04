package dev.blokz.arxiver.core.ai

import kotlinx.coroutines.flow.Flow

/**
 * A single on-device inference back-end (SPEC-AI-PROVIDERS §3). Implementations
 * wrap a concrete runtime — [GemmaEngine] + [QwenEngine] (LiteRT-LM), [NanoEngine]
 * (ML Kit GenAI) — behind one shape so [OnDeviceProvider] can pick the
 * best ready engine without knowing the runtime.
 */
interface OnDeviceEngine {
    val tier: InferenceTier

    /**
     * The output richness this engine can emit reliably (P-Atlas PA.2) — abstract so each engine
     * makes a conscious choice (a new engine can't silently inherit the wrong tier). Gemma-class →
     * [OutputRichness.STRUCTURED] (tables); tiny models (Nano, the Qwen light tier) → [OutputRichness.PLAIN].
     */
    val richness: OutputRichness

    /** True when this engine can serve a request right now (model installed / available). */
    suspend fun isReady(): Boolean

    fun generate(request: ChatRequest): Flow<ChatChunk>
}
