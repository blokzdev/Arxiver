package dev.blokz.arxiver.core.claude

import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RoutineTriggerClientTest {
    private lateinit var server: MockWebServer

    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Default
        }

    private fun client(timeoutsMs: Long = 10_000) =
        RoutineTriggerClient(
            httpClient =
                OkHttpClient.Builder()
                    .callTimeout(timeoutsMs, TimeUnit.MILLISECONDS)
                    .build(),
            dispatchers = dispatchers,
        )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url() = server.url("/v1/claude_code/routines/trig_abc/fire").toString()

    /** Wire format verified against the live fire endpoint (task 4.8). */
    @Test
    fun `2xx is accepted and request matches the real fire contract`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200))

            val payload = """{"action":"ping","schema":"arxiver/v1"}"""
            val outcome = client().send(url(), token = "tok_secret", payloadJson = payload)

            assertIs<TriggerOutcome.Accepted>(outcome)
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/v1/claude_code/routines/trig_abc/fire", recorded.path)
            assertEquals("Bearer tok_secret", recorded.getHeader("Authorization"))
            assertEquals(RoutineTriggerClient.ANTHROPIC_VERSION, recorded.getHeader("anthropic-version"))
            assertEquals(RoutineTriggerClient.ANTHROPIC_BETA, recorded.getHeader("anthropic-beta"))
            assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))

            // Body is {"text": "<arxiver payload>"} — the payload rides as a text turn.
            val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            assertEquals(payload, body.getValue("text").jsonPrimitive.content)
        }

    @Test
    fun `trigger url without fire suffix is normalized`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200))
            val bareUrl = server.url("/v1/claude_code/routines/trig_abc").toString()

            client().send(bareUrl, "t", "{}")

            assertEquals("/v1/claude_code/routines/trig_abc/fire", server.takeRequest().path)
        }

    @Test
    fun `normalizeTriggerUrl handles suffix and trailing-slash variants`() {
        val fire = "https://api.anthropic.com/v1/claude_code/routines/trig_x/fire"
        assertEquals(fire, RoutineTriggerClient.normalizeTriggerUrl(fire))
        assertEquals(fire, RoutineTriggerClient.normalizeTriggerUrl(fire.removeSuffix("/fire")))
        assertEquals(fire, RoutineTriggerClient.normalizeTriggerUrl(fire.removeSuffix("/fire") + "/"))
        assertEquals(fire, RoutineTriggerClient.normalizeTriggerUrl("  $fire  "))
        // Non-routine URLs pass through untouched.
        assertEquals("https://example.com/hook", RoutineTriggerClient.normalizeTriggerUrl("https://example.com/hook"))
    }

    @Test
    fun `401 maps to auth rejection`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401))
            assertIs<TriggerOutcome.AuthRejected>(client().send(url(), "t", "{}"))
        }

    @Test
    fun `400 maps to permanent rejection`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(400))
            assertIs<TriggerOutcome.Rejected>(client().send(url(), "t", "{}"))
        }

    @Test
    fun `404 maps to permanent rejection`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))
            assertIs<TriggerOutcome.Rejected>(client().send(url(), "t", "{}"))
        }

    @Test
    fun `5xx maps to retryable`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503))
            val outcome = assertIs<TriggerOutcome.Retryable>(client().send(url(), "t", "{}"))
            assertEquals(503, outcome.httpCode)
        }

    @Test
    fun `network failure maps to retryable`() =
        runTest {
            server.shutdown()
            assertIs<TriggerOutcome.Retryable>(client(timeoutsMs = 500).send(url(), "t", "{}"))
        }
}
