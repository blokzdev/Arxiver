package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Gemini (Google Generative Language API) BYOK transport
 * (SPEC-AI-PROVIDERS §3, tier 3).
 *
 * ```
 * POST {base}/models/{model}:streamGenerateContent?alt=sse
 * x-goog-api-key: <user key>
 * content-type: application/json
 * {"contents":[{role, parts:[{text}]}], "systemInstruction"?, "generationConfig"}
 * ```
 *
 * Like [AnthropicProvider], this is **not** Firebase AI Logic — it hits the
 * REST endpoint directly with the end-user's key. SSE `data:` lines are
 * `GenerateContentResponse` objects; text lives at
 * `candidates[0].content.parts[0].text`. There is no explicit stop event — the
 * stream simply ends, with `finishReason` on the final chunk.
 */
class GeminiProvider(
    private val httpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
    private val apiKey: () -> String?,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : AiProvider {
    override val id: ProviderId = ProviderId.GEMINI

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

                var finishReason: String? = null
                var toolSeq = 0 // Gemini gives no tool-call id; synthesize a stable per-stream one.
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

                    val event = runCatching { json.decodeFromString<GenResponse>(data) }.getOrNull() ?: continue
                    val candidate = event.candidates.firstOrNull()
                    // Iterate ALL parts (was firstOrNull().text, which dropped functionCall parts).
                    candidate?.content?.parts?.forEach { part ->
                        when {
                            part.text != null -> emit(ChatChunk.Delta(part.text))
                            part.functionCall != null ->
                                emit(
                                    ChatChunk.ToolUse(
                                        id = "gemini-tool-${toolSeq++}",
                                        name = part.functionCall.name,
                                        inputJson = part.functionCall.args.toString(),
                                    ),
                                )
                        }
                    }
                    candidate?.finishReason?.let { finishReason = it }
                }
                emit(ChatChunk.Done(finishReason))
            }
        }.flowOn(dispatchers.io)

    private fun buildRequest(
        request: ChatRequest,
        key: String,
    ): Request {
        val systemParts =
            buildList {
                request.system?.let { add(GenPart(it)) }
                request.messages.filter { it.role == ChatRole.SYSTEM }.forEach { add(GenPart(it.content)) }
            }
        // Null when no tools ⇒ tools/toolConfig omitted (encodeDefaults off) ⇒ byte-identical wire.
        val genTools =
            request.tools.takeIf { it.isNotEmpty() }
                ?.map { GenFunctionDeclaration(it.name, it.description, it.inputSchema) }
                ?.let { listOf(GenTool(functionDeclarations = it)) }
        val body =
            GenRequest(
                contents =
                    request.messages
                        .filter { it.role != ChatRole.SYSTEM }
                        .map { msg ->
                            // Text+image path unchanged (byte-identical to pre-R3d); tool turns emit
                            // functionCall (assistant) / functionResponse (TOOL) parts instead.
                            val parts =
                                buildList {
                                    when {
                                        msg.toolCalls.isNotEmpty() ->
                                            msg.toolCalls.forEach {
                                                add(
                                                    GenPart(
                                                        functionCall =
                                                            GenFunctionCall(
                                                                it.name,
                                                                parseArgs(it.inputJson),
                                                            ),
                                                    ),
                                                )
                                            }
                                        msg.toolResults.isNotEmpty() ->
                                            msg.toolResults.forEach {
                                                add(
                                                    GenPart(
                                                        functionResponse =
                                                            GenFunctionResponse(it.name, wrapResponse(it.contentJson)),
                                                    ),
                                                )
                                            }
                                        else -> {
                                            add(GenPart(text = msg.content))
                                            msg.images.forEach {
                                                add(GenPart(inlineData = InlineData(it.mediaType, it.base64)))
                                            }
                                        }
                                    }
                                }
                            GenContent(role = msg.role.wire(), parts = parts)
                        },
                systemInstruction = systemParts.takeIf { it.isNotEmpty() }?.let { GenContent(parts = it) },
                generationConfig = GenConfig(maxOutputTokens = request.maxTokens),
                tools = genTools,
                toolConfig = genTools?.let { GenToolConfig(GenFunctionCallingConfig("AUTO")) },
            )
        return Request.Builder()
            .url("$baseUrl/models/$model:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", key)
            .header("content-type", "application/json")
            .post(json.encodeToString(GenRequest.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun ChatRole.wire(): String =
        when (this) {
            ChatRole.ASSISTANT -> "model"
            else -> "user"
        }

    /** A functionCall args string → a JSON object; blank (zero-arg tool) → `{}`. */
    private fun parseArgs(inputJson: String): JsonObject =
        if (inputJson.isBlank()) {
            JsonObject(emptyMap())
        } else {
            runCatching { json.parseToJsonElement(inputJson).jsonObject }
                .getOrElse { throw AiException(AppError.Unexpected()) }
        }

    /** Gemini's functionResponse.response MUST be an object; wrap a bare/non-object result. */
    private fun wrapResponse(contentJson: String): JsonObject =
        runCatching { json.parseToJsonElement(contentJson).jsonObject }
            .getOrElse { buildJsonObject { put("result", contentJson) } }

    private fun Int.toAppError(): AppError =
        when {
            this == 401 || this == 403 -> AppError.Upstream(this, "authentication failed")
            this == 429 -> AppError.RateLimited
            this in 500..599 -> AppError.Upstream(this, "upstream error")
            else -> AppError.Upstream(this)
        }

    @Serializable
    private data class GenRequest(
        val contents: List<GenContent>,
        val systemInstruction: GenContent? = null,
        val generationConfig: GenConfig,
        val tools: List<GenTool>? = null,
        val toolConfig: GenToolConfig? = null,
    )

    @Serializable
    private data class GenTool(val functionDeclarations: List<GenFunctionDeclaration>)

    @Serializable
    private data class GenFunctionDeclaration(
        val name: String,
        val description: String,
        val parameters: JsonObject,
    )

    @Serializable
    private data class GenToolConfig(val functionCallingConfig: GenFunctionCallingConfig)

    @Serializable
    private data class GenFunctionCallingConfig(val mode: String)

    @Serializable
    private data class GenFunctionCall(val name: String, val args: JsonObject = JsonObject(emptyMap()))

    @Serializable
    private data class GenFunctionResponse(val name: String, val response: JsonObject)

    @Serializable
    private data class GenConfig(val maxOutputTokens: Int)

    @Serializable
    private data class GenResponse(val candidates: List<GenCandidate> = emptyList())

    @Serializable
    private data class GenCandidate(val content: GenContent? = null, val finishReason: String? = null)

    @Serializable
    private data class GenContent(
        val role: String? = null,
        val parts: List<GenPart> = emptyList(),
    )

    @Serializable
    private data class GenPart(
        val text: String? = null,
        val inlineData: InlineData? = null,
        val functionCall: GenFunctionCall? = null,
        val functionResponse: GenFunctionResponse? = null,
    )

    /** Gemini inline image bytes (P-Rich R3d): base64 [data] + its [mimeType]. */
    @Serializable
    private data class InlineData(
        val mimeType: String,
        val data: String,
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

        /**
         * Pinned model id (SPEC-AI-PROVIDERS §8 — model selection is a later enhancement).
         * Google retires older Gemini models: `gemini-2.0-flash` began returning HTTP 404
         * ("no longer available"), which surfaced as a generic connection error. Keep this
         * on a current model and revisit when Google deprecates it.
         */
        const val DEFAULT_MODEL = "gemini-2.5-flash"

        /** Gemini's input window; used for tier/UI gating, not request-shaping. */
        private const val CONTEXT_TOKENS = 1_000_000

        private const val DATA_PREFIX = "data:"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
