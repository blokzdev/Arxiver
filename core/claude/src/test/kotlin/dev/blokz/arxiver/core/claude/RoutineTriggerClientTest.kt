package dev.blokz.arxiver.core.claude

import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
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

    private fun url() = server.url("/routines/abc/trigger").toString()

    @Test
    fun `2xx is accepted and sends bearer token plus json body`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200))

            val outcome = client().send(url(), token = "tok_secret", payloadJson = """{"action":"ping"}""")

            assertIs<TriggerOutcome.Accepted>(outcome)
            val recorded = server.takeRequest()
            assertEquals("Bearer tok_secret", recorded.getHeader("Authorization"))
            assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))
            assertEquals("""{"action":"ping"}""", recorded.body.readUtf8())
            assertEquals("POST", recorded.method)
        }

    @Test
    fun `401 maps to auth rejection`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401))
            assertIs<TriggerOutcome.AuthRejected>(client().send(url(), "t", "{}"))
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
