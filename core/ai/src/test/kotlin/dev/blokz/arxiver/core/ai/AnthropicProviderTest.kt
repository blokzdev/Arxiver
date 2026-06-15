package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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

    private fun request(): ChatRequest =
        ChatRequest(
            messages = listOf(ChatMessage(ChatRole.USER, "Summarize this paper.")),
            system = "You are a research assistant.",
        )
}
