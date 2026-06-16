package dev.blokz.arxiver.chat

import dev.blokz.arxiver.core.ai.ChatRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The exact content that will leave the device for a cloud call (SPEC-AI-PROVIDERS §5). */
data class ChatPreview(
    /** Human-readable rendering for the confirm sheet. */
    val text: String,
    /** Stable JSON of the request body content — drives the redaction golden test. */
    val json: String,
)

@Serializable
private data class PreviewMessageDto(val role: String, val content: String)

@Serializable
private data class PreviewDto(
    val system: String?,
    val messages: List<PreviewMessageDto>,
    val maxTokens: Int,
)

/**
 * Renders the assembled [ChatRequest] into a faithful "what leaves the device"
 * preview (SPEC-AI-PROVIDERS §5). It shows exactly the system instruction +
 * messages (with their folded context) that the provider will transmit. Provider
 * keys live only in the HTTP header, never in this body, so they are structurally
 * absent; gated note content was already excluded upstream by the assembler. Uses
 * the `explicitNulls = false` redaction config (mirrors `core/claude` PayloadBuilder)
 * so an absent system is omitted rather than serialized as null.
 */
class ChatPreviewBuilder(
    private val json: Json =
        Json {
            prettyPrint = true
            explicitNulls = false
        },
) {
    fun build(request: ChatRequest): ChatPreview {
        val dto =
            PreviewDto(
                system = request.system,
                messages = request.messages.map { PreviewMessageDto(it.role.name.lowercase(), it.content) },
                maxTokens = request.maxTokens,
            )
        return ChatPreview(text = render(request), json = json.encodeToString(PreviewDto.serializer(), dto))
    }

    private fun render(request: ChatRequest): String =
        buildString {
            appendLine("WHAT LEAVES THE DEVICE")
            appendLine("Sent to the selected cloud provider. Your API key is sent in a header, never in this body.")
            appendLine()
            request.system?.let {
                appendLine("SYSTEM:")
                appendLine(it)
                appendLine()
            }
            request.messages.forEach { msg ->
                appendLine("${msg.role.name}:")
                appendLine(msg.content)
                appendLine()
            }
        }.trimEnd()
}
