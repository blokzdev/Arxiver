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
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** MockWebServer coverage for the Gemini transport (SPEC-AI-PROVIDERS §7). */
class GeminiProviderTest {
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

    private fun provider(key: String? = "g-test"): GeminiProvider =
        GeminiProvider(
            httpClient = OkHttpClient(),
            dispatchers = dispatchers,
            apiKey = { key },
            baseUrl = server.url("/v1beta").toString().trimEnd('/'),
        )

    private fun sse(vararg events: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(events.joinToString("") { "$it\n\n" })

    @Test
    fun `streams deltas in order then completes on stream end`() =
        runBlocking {
            server.enqueue(
                sse(
                    """data: {"candidates":[{"content":{"parts":[{"text":"Hel"}]}}]}""",
                    """data: {"candidates":[{"content":{"parts":[{"text":"lo"}]},"finishReason":"STOP"}]}""",
                ),
            )

            val chunks = provider().chat(request()).toList()

            val text = chunks.filterIsInstance<ChatChunk.Delta>().joinToString("") { it.text }
            assertEquals("Hello", text)
            val done = chunks.last()
            assertTrue(done is ChatChunk.Done)
            assertEquals("STOP", done.stopReason)
        }

    @Test
    fun `sends api key header and streamGenerateContent path`() =
        runBlocking {
            server.enqueue(
                sse("""data: {"candidates":[{"content":{"parts":[{"text":"hi"}]},"finishReason":"STOP"}]}"""),
            )

            provider(key = "g-secret").chat(request()).toList()

            val recorded = server.takeRequest()
            assertEquals("g-secret", recorded.getHeader("x-goog-api-key"))
            assertTrue(recorded.path!!.contains(":streamGenerateContent?alt=sse"), recorded.path)
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("\"role\":\"user\""), body)
            assertTrue(body.contains("systemInstruction"), body)
            assertTrue(body.contains("Summarize"), body)
        }

    @Test
    fun `auth rejection maps to upstream error`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))

            val error = assertFailsWith<AiException> { provider().chat(request()).toList() }.error
            assertTrue(error is AppError.Upstream)
            assertEquals(403, error.httpCode)
        }

    @Test
    fun `server error maps to upstream`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))

            val error = assertFailsWith<AiException> { provider().chat(request()).toList() }.error
            assertTrue(error is AppError.Upstream)
            assertEquals(500, error.httpCode)
        }

    @Test
    fun `disconnect mid-stream surfaces Offline`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    .setBody("""data: {"candidates":[{"content":{"parts":[{"text":"x"}]}}]}"""),
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
    fun `text-only turn omits inlineData (R3d byte-identity)`() =
        runBlocking {
            server.enqueue(sse("""data: {"candidates":[{"content":{"parts":[{"text":"x"}]},"finishReason":"STOP"}]}"""))
            provider().chat(request()).toList()

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"text\":\"Summarize this paper.\""), body)
            assertTrue(!body.contains("inlineData"), body)
        }

    @Test
    fun `an image turn serializes an inlineData part, never the label (R3d vision)`() =
        runBlocking {
            server.enqueue(sse("""data: {"candidates":[{"content":{"parts":[{"text":"x"}]},"finishReason":"STOP"}]}"""))
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
            assertTrue(body.contains("\"text\":\"Describe the figure.\""), body)
            assertTrue(body.contains("\"inlineData\""), body)
            assertTrue(body.contains("\"mimeType\":\"image/jpeg\""), body)
            assertTrue(body.contains("\"data\":\"QUJD\""), body)
            assertTrue(!body.contains("page 2 of arXiv"), body)
        }

    // --- P-Tools PT.0: functionCall parsing + tool wire serialization ---

    private fun toolDef() = ToolDef("echo", "Echo text", buildJsonObject { put("type", "object") })

    private fun candidate(parts: String) =
        """data: {"candidates":[{"content":{"parts":[$parts]},"finishReason":"STOP"}]}"""

    private fun fnCall(args: String) = """{"functionCall":{"name":"echo","args":$args}}"""

    @Test
    fun `functionCall part emits ToolUse with a synthesized id`() =
        runBlocking {
            server.enqueue(sse(candidate(fnCall("""{"text":"hi"}"""))))
            val tool = provider().chat(request()).toList().filterIsInstance<ChatChunk.ToolUse>().single()
            assertEquals("gemini-tool-0", tool.id)
            assertEquals("echo", tool.name)
            assertTrue(tool.inputJson.contains("\"text\":\"hi\""), tool.inputJson)
        }

    @Test
    fun `a multi-part response emits text then ToolUse`() =
        runBlocking {
            server.enqueue(sse(candidate("""{"text":"searching"},""" + fnCall("{}"))))
            val chunks = provider().chat(request()).toList()
            assertEquals("searching", chunks.filterIsInstance<ChatChunk.Delta>().single().text)
            assertEquals("echo", chunks.filterIsInstance<ChatChunk.ToolUse>().single().name)
        }

    @Test
    fun `a malformed functionCall is dropped without hanging`() =
        runBlocking {
            server.enqueue(
                sse(
                    """data: {"candidates":[{"content":{"parts":[{"functionCall":{"args":{}}}]}}]}""",
                    """data: {"candidates":[{"finishReason":"STOP"}]}""",
                ),
            )
            val chunks = provider().chat(request()).toList()
            assertTrue(chunks.none { it is ChatChunk.ToolUse }, "malformed functionCall yields no ToolUse")
            assertTrue(chunks.last() is ChatChunk.Done)
        }

    @Test
    fun `no tools attached omits tools and toolConfig (byte-identity)`() =
        runBlocking {
            server.enqueue(sse("""data: {"candidates":[{"content":{"parts":[{"text":"x"}]},"finishReason":"STOP"}]}"""))
            provider().chat(request()).toList()
            val body = server.takeRequest().body.readUtf8()
            assertTrue(!body.contains("\"tools\""), body)
            assertTrue(!body.contains("toolConfig"), body)
        }

    @Test
    fun `tool defs serialize as functionDeclarations plus AUTO toolConfig`() =
        runBlocking {
            server.enqueue(sse("""data: {"candidates":[{"content":{"parts":[{"text":"x"}]},"finishReason":"STOP"}]}"""))
            provider().chat(request().copy(tools = listOf(toolDef()))).toList()
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("functionDeclarations"), body)
            assertTrue(body.contains("\"mode\":\"AUTO\""), body)
            assertTrue(body.contains("\"echo\""), body)
        }

    @Test
    fun `a functionResponse turn serializes an object response`() =
        runBlocking {
            server.enqueue(sse("""data: {"candidates":[{"content":{"parts":[{"text":"x"}]},"finishReason":"STOP"}]}"""))
            val req =
                ChatRequest(
                    messages =
                        listOf(
                            ChatMessage(ChatRole.USER, "call echo"),
                            ChatMessage(
                                ChatRole.ASSISTANT,
                                "",
                                toolCalls = listOf(ToolCall("gemini-tool-0", "echo", """{"text":"hi"}""")),
                            ),
                            ChatMessage(
                                ChatRole.TOOL,
                                "",
                                toolResults = listOf(ToolResult("gemini-tool-0", "echo", """{"echo":"hi"}""")),
                            ),
                        ),
                )
            provider().chat(req).toList()
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("functionCall"), body)
            assertTrue(body.contains("functionResponse"), body)
            assertTrue(body.contains("\"echo\":\"hi\""), body)
        }

    private fun request(): ChatRequest =
        ChatRequest(
            messages = listOf(ChatMessage(ChatRole.USER, "Summarize this paper.")),
            system = "You are a research assistant.",
        )
}
