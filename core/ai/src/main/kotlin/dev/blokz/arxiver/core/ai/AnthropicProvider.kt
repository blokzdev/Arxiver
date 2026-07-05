package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Claude (Anthropic Messages API) BYOK transport (SPEC-AI-PROVIDERS §3, tier 3).
 *
 * ```
 * POST https://api.anthropic.com/v1/messages
 * x-api-key: <user key>
 * anthropic-version: 2023-06-01
 * content-type: application/json
 * {"model", "max_tokens", "system"?, "stream":true, "messages":[{role, content}]}
 * ```
 *
 * The response is an SSE stream; we parse it manually line-by-line over the
 * OkHttp body (no `okhttp-sse` dep — matches the light client style of
 * `ArxivApiClient`/`SemanticScholarClient`). The key is supplied lazily via
 * [apiKey] so the transport stays decoupled from key storage and testable
 * without `EncryptedSharedPreferences`.
 */
class AnthropicProvider(
    private val httpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
    private val apiKey: () -> String?,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : AiProvider {
    override val id: ProviderId = ProviderId.CLAUDE

    override val capability: ProviderCapability =
        ProviderCapability(
            contextTokens = CONTEXT_TOKENS,
            streaming = true,
            onDevice = false,
            requiresKey = true,
            richness = OutputRichness.FULL,
            vision = true,
            supportsTools = true,
        )

    private val json = Json { ignoreUnknownKeys = true }

    override fun chat(request: ChatRequest): Flow<ChatChunk> =
        flow {
            val key = apiKey() ?: throw AiException(AppError.Unexpected())
            val httpRequest = buildRequest(request, key)

            val response =
                try {
                    httpClient.newCall(httpRequest).execute()
                } catch (e: IOException) {
                    throw AiException(AppError.Offline)
                }

            response.use { resp ->
                if (!resp.isSuccessful) throw AiException(resp.code.toAppError())
                val source = resp.body?.source() ?: throw AiException(AppError.Upstream(resp.code, "empty body"))

                var stopReason: String? = null
                // Tool-block accumulator (P-Tools PT.0): a tool_use content block streams as
                // content_block_start (type+id+name) → N× content_block_delta.input_json_delta.partial_json
                // → content_block_stop. Text blocks leave inToolBlock false, so the text path is unchanged.
                var toolId: String? = null
                var toolName: String? = null
                val toolArgs = StringBuilder()
                var inToolBlock = false
                while (true) {
                    val line =
                        try {
                            source.readUtf8Line()
                        } catch (e: IOException) {
                            throw AiException(AppError.Offline)
                        } ?: break
                    if (!line.startsWith(DATA_PREFIX)) continue
                    val data = line.removePrefix(DATA_PREFIX).trim()
                    if (data.isEmpty()) continue

                    val event = runCatching { json.decodeFromString<StreamEvent>(data) }.getOrNull() ?: continue
                    when (event.type) {
                        "content_block_start" ->
                            if (event.contentBlock?.type == "tool_use") {
                                if (inToolBlock) throw AiException(AppError.Upstream(resp.code, "interleaved tool_use"))
                                inToolBlock = true
                                toolId = event.contentBlock.id
                                toolName = event.contentBlock.name
                                toolArgs.setLength(0)
                            } else {
                                inToolBlock = false // a non-tool block start resets any stale tool state
                            }
                        "content_block_delta" -> {
                            event.delta?.text?.let { emit(ChatChunk.Delta(it)) }
                            if (inToolBlock) event.delta?.partialJson?.let { toolArgs.append(it) }
                        }
                        "content_block_stop" ->
                            if (inToolBlock) {
                                val id = toolId
                                val name = toolName
                                if (id == null || name == null) {
                                    throw AiException(AppError.Upstream(resp.code, "malformed tool_use block"))
                                }
                                emit(ChatChunk.ToolUse(id, name, toolArgs.toString()))
                                inToolBlock = false
                                toolId = null
                                toolName = null
                                toolArgs.setLength(0)
                            }
                        "message_delta" -> event.delta?.stopReason?.let { stopReason = it }
                        "error" -> throw AiException(AppError.Upstream(resp.code, event.error?.message))
                        "message_stop" -> {
                            // A tool_use block still open at message_stop is a corrupted stream — fail
                            // loudly rather than let the loop wait on a tool_result that never comes.
                            if (inToolBlock) throw AiException(AppError.Upstream(resp.code, "unterminated tool_use"))
                            emit(ChatChunk.Done(stopReason))
                            return@flow
                        }
                    }
                }
                if (inToolBlock) throw AiException(AppError.Upstream(resp.code, "truncated tool_use"))
                emit(ChatChunk.Done(stopReason))
            }
        }.flowOn(dispatchers.io)

    private fun buildRequest(
        request: ChatRequest,
        key: String,
    ): Request {
        val systemParts =
            buildList {
                request.system?.let { add(it) }
                request.messages.filter { it.role == ChatRole.SYSTEM }.forEach { add(it.content) }
            }
        val body =
            WireRequest(
                model = model,
                maxTokens = request.maxTokens,
                system = systemParts.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
                stream = true,
                messages =
                    request.messages
                        .filter { it.role != ChatRole.SYSTEM }
                        .map { WireMessage(role = it.role.wire(), content = anthropicContent(it)) },
                // takeIf BEFORE map ⇒ null (omitted) not [] when no tools — byte-identical wire.
                tools =
                    request.tools.takeIf { it.isNotEmpty() }?.map {
                        WireTool(
                            it.name,
                            it.description,
                            it.inputSchema,
                        )
                    },
            )
        return Request.Builder()
            .url("$baseUrl/messages")
            .header("x-api-key", key)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("content-type", "application/json")
            .post(json.encodeToString(WireRequest.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun ChatRole.wire(): String =
        when (this) {
            ChatRole.ASSISTANT -> "assistant"
            else -> "user"
        }

    /**
     * A message's wire content: text-only turns serialize as a JSON **string** (byte-identical to
     * pre-R3d); turns with images serialize as Anthropic's content-block **array** (text + base64
     * image blocks). [ChatImage.label] is never written (only media type + data).
     */
    private fun anthropicContent(message: ChatMessage): JsonElement =
        when {
            // Assistant tool_use turn: text (if any) then one tool_use block per call. REQUIRED on
            // resume — a tool_result without a matching tool_use in the prior assistant turn is a 400.
            message.toolCalls.isNotEmpty() ->
                buildJsonArray {
                    if (message.content.isNotEmpty()) {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", message.content)
                            },
                        )
                    }
                    message.toolCalls.forEach { call ->
                        add(
                            buildJsonObject {
                                put("type", "tool_use")
                                put("id", call.id)
                                put("name", call.name)
                                put("input", parseToolInput(call.inputJson))
                            },
                        )
                    }
                }
            // TOOL turn: one tool_result block per executed result (carried on a `user`-role message).
            message.toolResults.isNotEmpty() ->
                buildJsonArray {
                    message.toolResults.forEach { result ->
                        add(
                            buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", result.callId)
                                put("content", result.contentJson)
                                if (result.isError) put("is_error", true)
                            },
                        )
                    }
                }
            message.images.isEmpty() -> JsonPrimitive(message.content)
            else ->
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", message.content)
                        },
                    )
                    message.images.forEach { img ->
                        add(
                            buildJsonObject {
                                put("type", "image")
                                put(
                                    "source",
                                    buildJsonObject {
                                        put("type", "base64")
                                        put("media_type", img.mediaType)
                                        put("data", img.base64)
                                    },
                                )
                            },
                        )
                    }
                }
        }

    /** A tool_use arguments string → a JSON object for the wire; blank (zero-arg tool) → `{}`. */
    private fun parseToolInput(inputJson: String): JsonElement =
        if (inputJson.isBlank()) {
            buildJsonObject { }
        } else {
            runCatching { json.parseToJsonElement(inputJson) }.getOrElse { throw AiException(AppError.Unexpected()) }
        }

    private fun Int.toAppError(): AppError =
        when {
            this == 401 || this == 403 -> AppError.Upstream(this, "authentication failed")
            this == 429 -> AppError.RateLimited
            this in 500..599 -> AppError.Upstream(this, "upstream error")
            else -> AppError.Upstream(this)
        }

    @Serializable
    private data class WireRequest(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        val system: String? = null,
        val stream: Boolean,
        val messages: List<WireMessage>,
        // Null when no tools attach ⇒ omitted (encodeDefaults is off) ⇒ byte-identical wire.
        val tools: List<WireTool>? = null,
    )

    @Serializable
    private data class WireTool(
        val name: String,
        val description: String,
        @SerialName("input_schema") val inputSchema: JsonObject,
    )

    @Serializable
    private data class WireMessage(val role: String, val content: JsonElement)

    @Serializable
    private data class StreamEvent(
        val type: String,
        val delta: EventDelta? = null,
        val error: EventError? = null,
        @SerialName("content_block") val contentBlock: ContentBlock? = null,
    )

    @Serializable
    private data class ContentBlock(
        val type: String? = null,
        val id: String? = null,
        val name: String? = null,
    )

    @Serializable
    private data class EventDelta(
        val type: String? = null,
        val text: String? = null,
        @SerialName("stop_reason") val stopReason: String? = null,
        @SerialName("partial_json") val partialJson: String? = null,
    )

    @Serializable
    private data class EventError(val type: String? = null, val message: String? = null)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com/v1"
        const val ANTHROPIC_VERSION = "2023-06-01"

        /** Pinned model id (SPEC-AI-PROVIDERS §8 — model selection is a later enhancement). */
        const val DEFAULT_MODEL = "claude-sonnet-4-6"

        /** Claude's input window; used for tier/UI gating, not request-shaping. */
        private const val CONTEXT_TOKENS = 200_000

        private const val DATA_PREFIX = "data:"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
