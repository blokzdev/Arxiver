package dev.blokz.arxiver.chat

import dev.blokz.arxiver.core.ai.ChatImage
import dev.blokz.arxiver.core.ai.ChatRequest
import dev.blokz.arxiver.data.tool.ToolRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The tools that leave the device (P-Tools PT.2). The confirm sheet must disclose an external tool's
 * egress honestly ("sends your query to arXiv") vs a local one's ("searches your device") — so the
 * classification lives here, keyed off the registry's tool-name constants (single source of truth).
 */
private val EXTERNAL_TOOL_NAMES =
    setOf(
        ToolRegistry.SEARCH_ARXIV_NAME,
        ToolRegistry.GET_PAPER_NAME,
        ToolRegistry.IMPORT_NAME,
    )

/** The exact content that will leave the device for a cloud call (SPEC-AI-PROVIDERS §5). */
data class ChatPreview(
    /** Human-readable rendering for the confirm sheet. */
    val text: String,
    /** Stable JSON of the request body content — drives the redaction golden test. */
    val json: String,
)

@Serializable
private data class PreviewMessageDto(
    val role: String,
    val content: String,
    /** Human disclosures of attached images (P-Rich R3d) — the base64 bytes are NEVER included.
     *  Omitted (null) for text-only turns, so the redaction golden stays byte-identical. */
    val attachments: List<String>? = null,
)

/** A faithful disclosure of an attached image — page/paper label, mime, approx size; never the data. */
private fun ChatImage.disclosure(): String {
    val approxKb = (base64.length.toLong() * 3 / 4 / 1024).coerceAtLeast(1)
    return "Attached image: ${label ?: "image"} — $mediaType, ~$approxKb KB"
}

/** A tool the model may call (P-Tools PT.0) — name + description leave the device; schema is elided.
 *  [egress] distinguishes an external (leaves-the-device) tool from a purely on-device one (PT.2). */
@Serializable
private data class PreviewToolDto(
    val name: String,
    val description: String,
    val egress: Boolean,
)

@Serializable
private data class PreviewDto(
    val system: String?,
    val messages: List<PreviewMessageDto>,
    val maxTokens: Int,
    /** Tools offered to the model (P-Tools PT.0). Null (omitted) when none — the redaction golden
     *  stays byte-identical for a tool-free turn. */
    val tools: List<PreviewToolDto>? = null,
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
                messages =
                    request.messages.map { msg ->
                        PreviewMessageDto(
                            role = msg.role.name.lowercase(),
                            content = msg.content,
                            attachments = msg.images.takeIf { it.isNotEmpty() }?.map { it.disclosure() },
                        )
                    },
                maxTokens = request.maxTokens,
                // takeIf BEFORE map ⇒ null (omitted) not [] when no tools — byte-identical golden.
                tools =
                    request.tools.takeIf { it.isNotEmpty() }?.map {
                        PreviewToolDto(it.name, it.description, egress = it.name in EXTERNAL_TOOL_NAMES)
                    },
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
            if (request.tools.isNotEmpty()) {
                appendLine("TOOLS THE MODEL MAY CALL")
                request.tools.forEach { tool ->
                    // Disclose each tool's egress honestly: an external tool sends your query off-device
                    // to a third party; a local tool searches only your device (P-Tools PT.2).
                    val effect =
                        if (tool.name in EXTERNAL_TOOL_NAMES) {
                            "sends your query to arXiv, a third party"
                        } else {
                            "searches your device, nothing leaves it"
                        }
                    appendLine("- ${tool.name} ($effect): ${tool.description}")
                }
                appendLine()
            }
            request.messages.forEach { msg ->
                appendLine("${msg.role.name}:")
                appendLine(msg.content)
                // Disclose attached images here too (P-Rich R3d) so the human-readable confirm the
                // user actually approves mentions the image — base64 bytes are never shown.
                msg.images.forEach { appendLine("[${it.disclosure()}]") }
                appendLine()
            }
        }.trimEnd()
}
