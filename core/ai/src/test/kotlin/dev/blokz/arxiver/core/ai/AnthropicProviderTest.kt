package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MockWebServer coverage for the Claude transport (SPEC-AI-PROVIDERS §7).
 * Real-IO tests use [runBlocking] (not `runTest`) per the de-flake convention.
 */
class AnthropicProviderTest {
    private lateinit var server: MockWebServer

    private val dispatchers =
        object : DispatcherProvider {
            override val io = Dispatchers.Unconfined
            override val default = Dispatchers.Unconfined
            override val main = Dispatchers.Unconfined
        }

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(key: String? = "sk-test"): AnthropicProvider =
        AnthropicProvider(
            httpClient = OkHttpClient(),
            dispatchers = dispatchers,
            apiKey = { key },
            baseUrl = server.url("/v1").toString().trimEnd('/'),
        )

    private fun sse(vararg events: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(events.joinToString("") { "$it\n\n" })

    @Test
    fun `streams deltas in order then terminates on message_stop`() =
        runBlocking {
            server.enqueue(
                sse(
                    """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}""",
                    """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}""",
                    """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                    """data: {"type":"message_stop"}""",
                ),
            )

            val chunks = provider().chat(request()).toList()

            val text = chunks.filterIsInstance<ChatChunk.Delta>().joinToString("") { it.text }
            assertEquals("Hello", text)
            val done = chunks.last()
            assertTrue(done is ChatChunk.Done)
            assertEquals("end_turn", done.stopReason)
        }

    @Test
    fun `sends api key and messages without unexpected fields`() =
        runBlocking {
            server.enqueue(sse("""data: {"type":"message_stop"}"""))

            provider(key = "sk-secret").chat(request()).toList()

            val recorded = server.takeRequest()
            assertEquals("sk-secret", recorded.getHeader("x-api-key"))
            assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("\"stream\":true"), body)
            assertTrue(body.contains("\"role\":\"user\""), body)
            assertTrue(body.contains("Summarize"), body)
            // The Authorization/Bearer scheme belongs to the Routines bridge, not here.
            assertTrue(!body.contains("Bearer"), body)
        }

    @Test
    fun `auth rejection maps to upstream error`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))

            val error = assertFailsWith<AiException> { provider().chat(request()).toList() }.error
            assertTrue(error is AppError.Upstream)
            assertEquals(401, error.httpCode)
        }

    @Test
    fun `server error maps to upstream`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))

            val error = assertFailsWith<AiException> { provider().chat(request()).toList() }.error
            assertTrue(error is AppError.Upstream)
            assertEquals(503, error.httpCode)
        }

    @Test
    fun `rate limit maps to RateLimited`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))

            val error = assertFailsWith<AiException> { provider().chat(request()).toList() }.error
            assertEquals(AppError.RateLimited, error)
        }

    @Test
    fun `disconnect mid-stream surfaces Offline`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    .setBody("""data: {"type":"content_block_delta","delta":{"text":"x"}}"""),
            )

            val error = assertFailsWith<AiException> { provider().chat(request()).toList() }.error
            assertEquals(AppError.Offline, error)
        }

    @Test
    fun `missing key fails before any network call`() =
        runBlocking {
            val error = assertFailsWith<AiException> { provider(key = null).chat(request()).toList() }.error
            assertTrue(error is AppError.Unexpected)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `text-only turn serializes content as a string, not a block array (R3d byte-identity)`() =
        runBlocking {
            server.enqueue(sse("""data: {"type":"message_stop"}"""))
            provider().chat(request()).toList()

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"content\":\"Summarize this paper.\""), body)
            assertTrue(!body.contains("\"content\":["), body)
            assertTrue(!body.contains("\"type\":\"image\""), body)
        }

    @Test
    fun `an image turn serializes a text+image content array, never the label (R3d vision)`() =
        runBlocking {
            server.enqueue(sse("""data: {"type":"message_stop"}"""))
            val req =
                ChatRequest(
                    messages =
                        listOf(
                            ChatMessage(
                                ChatRole.USER,
                                "Describe the figure.",
                                images = listOf(ChatImage("image/jpeg", "QUJD", label = "page 2 of arXiv:2401.0001")),
                            ),
                        ),
                )
            provider().chat(req).toList()

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"content\":["), body)
            assertTrue(body.contains("\"type\":\"text\""), body)
            assertTrue(body.contains("\"type\":\"image\""), body)
            assertTrue(body.contains("\"media_type\":\"image/jpeg\""), body)
            assertTrue(body.contains("\"data\":\"QUJD\""), body)
            // The human privacy-preview label must never reach the wire.
            assertTrue(!body.contains("page 2 of arXiv"), body)
        }

    // --- P-Tools PT.0: tool-use SSE parsing + wire serialization ---
    // Compact SSE builders (the `index` field is ignored by the parser and dropped for brevity).

    private fun toolDef() = ToolDef("echo", "Echo text", buildJsonObject { put("type", "object") })

    private fun startTool(id: String) =
        """data: {"type":"content_block_start","content_block":{"type":"tool_use","id":"$id","name":"echo"}}"""

    private fun argDelta(value: String) =
        """data: {"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":"$value"}}"""

    private fun stopReason(reason: String) = """data: {"type":"message_delta","delta":{"stop_reason":"$reason"}}"""

    private val blockStop = """data: {"type":"content_block_stop"}"""
    private val msgStop = """data: {"type":"message_stop"}"""

    @Test
    fun `tool_use block accumulates partial_json across deltas and emits ToolUse`() =
        runBlocking {
            server.enqueue(
                sse(
                    startTool("toolu_1"),
                    argDelta("""{\"text\":"""),
                    argDelta("""\"hi\"}"""),
                    blockStop,
                    stopReason("tool_use"),
                    msgStop,
                ),
            )
            val chunks = provider().chat(request()).toList()
            val tool = chunks.filterIsInstance<ChatChunk.ToolUse>().single()
            assertEquals("toolu_1", tool.id)
            assertEquals("echo", tool.name)
            assertEquals("""{"text":"hi"}""", tool.inputJson)
            assertTrue(chunks.last() is ChatChunk.Done)
        }

    @Test
    fun `parallel tool_use blocks emit in order`() =
        runBlocking {
            server.enqueue(sse(startTool("a"), blockStop, startTool("b"), blockStop, msgStop))
            val ids = provider().chat(request()).toList().filterIsInstance<ChatChunk.ToolUse>().map { it.id }
            assertEquals(listOf("a", "b"), ids)
        }

    @Test
    fun `empty partial_json yields ToolUse with empty input string`() =
        runBlocking {
            server.enqueue(sse(startTool("z"), blockStop, msgStop))
            val tool = provider().chat(request()).toList().filterIsInstance<ChatChunk.ToolUse>().single()
            assertEquals("", tool.inputJson)
        }

    @Test
    fun `an unterminated tool_use at message_stop throws instead of hanging`() =
        runBlocking {
            server.enqueue(sse(startTool("z"), msgStop))
            val ex = assertFailsWith<AiException> { provider().chat(request()).toList() }
            assertTrue(ex.error is AppError.Upstream)
        }

    @Test
    fun `a truncated tool_use at stream end throws`() =
        runBlocking {
            server.enqueue(sse(startTool("z"), argDelta("""{""")))
            val ex = assertFailsWith<AiException> { provider().chat(request()).toList() }
            assertTrue(ex.error is AppError.Upstream)
        }

    @Test
    fun `an interleaved tool_use start throws`() =
        runBlocking {
            server.enqueue(sse(startTool("a"), startTool("b"), msgStop))
            val ex = assertFailsWith<AiException> { provider().chat(request()).toList() }
            assertTrue(ex.error is AppError.Upstream)
        }

    @Test
    fun `no tools attached omits the tools field (byte-identity)`() =
        runBlocking {
            server.enqueue(sse(msgStop))
            provider().chat(request()).toList()
            val body = server.takeRequest().body.readUtf8()
            assertTrue(!body.contains("\"tools\""), body)
        }

    @Test
    fun `tool defs serialize as input_schema when attached`() =
        runBlocking {
            server.enqueue(sse(msgStop))
            provider().chat(request().copy(tools = listOf(toolDef()))).toList()
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"tools\""), body)
            assertTrue(body.contains("\"input_schema\""), body)
            assertTrue(body.contains("\"echo\""), body)
        }

    @Test
    fun `assistant tool_use and tool_result turns serialize as content blocks`() =
        runBlocking {
            server.enqueue(sse(msgStop))
            val req =
                ChatRequest(
                    messages =
                        listOf(
                            ChatMessage(ChatRole.USER, "call echo"),
                            ChatMessage(
                                ChatRole.ASSISTANT,
                                "",
                                toolCalls = listOf(ToolCall("t1", "echo", """{"text":"hi"}""")),
                            ),
                            ChatMessage(
                                ChatRole.TOOL,
                                "",
                                toolResults = listOf(ToolResult("t1", "echo", """{"echo":"hi"}""")),
                            ),
                        ),
                )
            provider().chat(req).toList()
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"type\":\"tool_use\""), body)
            assertTrue(body.contains("\"tool_use_id\":\"t1\""), body)
            assertTrue(body.contains("\"type\":\"tool_result\""), body)
        }

    private fun request(): ChatRequest =
        ChatRequest(
            messages = listOf(ChatMessage(ChatRole.USER, "Summarize this paper.")),
            system = "You are a research assistant.",
        )
}
