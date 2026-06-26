package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.common.AppError
import kotlinx.coroutines.flow.Flow

/**
 * Provider-neutral conversational AI abstraction (SPEC-AI-PROVIDERS §2).
 * Claude, Gemini, and (later) on-device inference are interchangeable behind
 * this interface; adding a provider is additive — implement [AiProvider] and
 * register it in DI. Distinct from the Claude **Routines** bridge
 * (SPEC-CLAUDE-BRIDGE), which is an outbound trigger, not chat.
 */
interface AiProvider {
    val id: ProviderId

    val capability: ProviderCapability

    /**
     * Streams the assistant's reply token-by-token. The returned [Flow] emits
     * [ChatChunk.Delta]s as text arrives and a single [ChatChunk.Done] on a
     * clean finish. Transport/provider problems surface as [AiException]
     * (carrying an [AppError]) thrown into the flow — collectors map it to UI.
     */
    fun chat(request: ChatRequest): Flow<ChatChunk>
}

enum class ProviderId { CLAUDE, GEMINI, ON_DEVICE }

/**
 * Facts about a provider used for tier selection, prompt shaping, and UI gating.
 * [contextTokens] is the model's input window; [requiresKey] is true for the
 * BYOK cloud providers and false for on-device. [richness] is the per-engine output
 * tier (P-Atlas PA.2) — non-defaulted on purpose so every provider declares it; it is
 * the model-static value for cloud, but **resolved per-turn** for on-device (the
 * `OnDeviceProvider` wraps engines of differing richness — see `resolveRichness`).
 */
data class ProviderCapability(
    val contextTokens: Int,
    val streaming: Boolean,
    val onDevice: Boolean,
    val requiresKey: Boolean,
    /** Output richness tier (P-Atlas PA.2): PLAIN tiny / STRUCTURED Gemma / FULL cloud. */
    val richness: OutputRichness,
    /** Whether the model accepts image input (P-Rich R3d); on-device stays text-only. */
    val vision: Boolean = false,
)

enum class ChatRole { SYSTEM, USER, ASSISTANT }

/**
 * An image attached to a chat turn (P-Rich R3d vision). [base64] is RFC-4648 (NO_WRAP) image bytes;
 * [mediaType] their MIME (e.g. `image/jpeg`). [label] is a human description for the privacy preview
 * (e.g. "page 2 of arXiv:2401.0001") and is NEVER serialized to the wire — providers read only
 * [mediaType]/[base64].
 */
data class ChatImage(
    val mediaType: String,
    val base64: String,
    val label: String? = null,
)

/** A chat turn. [images] is empty for text-only turns, so existing callers + wire bytes are unchanged. */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val images: List<ChatImage> = emptyList(),
)

/**
 * A provider-neutral chat turn. Retrieved RAG context is folded into
 * [messages] by the caller (P2); each provider impl renders these into its own
 * wire format. [system] is an optional system instruction.
 */
data class ChatRequest(
    val messages: List<ChatMessage>,
    val system: String? = null,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 1024
    }
}

/** A streamed unit of an assistant reply. */
sealed interface ChatChunk {
    /** A piece of generated text. */
    data class Delta(val text: String) : ChatChunk

    /** Terminal marker; [stopReason] is the provider's stop reason when known. */
    data class Done(val stopReason: String? = null) : ChatChunk
}

/**
 * Carries an [AppError] across the streaming boundary. Conventions keep
 * exceptions inside a module, but a [Flow] needs an in-band failure channel;
 * collectors are expected to `catch` this and map [error] to UI state.
 */
class AiException(val error: AppError) : Exception()
