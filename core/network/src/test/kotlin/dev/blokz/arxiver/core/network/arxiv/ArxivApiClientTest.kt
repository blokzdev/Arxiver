package dev.blokz.arxiver.core.network.arxiv

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
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
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArxivApiClientTest {
    private lateinit var server: MockWebServer

    // Real dispatchers: MockWebServer does real I/O, virtual time doesn't apply.
    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Default
        }

    private fun fixtureXml() =
        requireNotNull(javaClass.getResourceAsStream("/arxiv_feed_sample.xml")).readBytes().decodeToString()

    private fun client(retryDelaysMs: List<Long> = listOf(1, 2)) =
        ArxivApiClient(
            httpClient = OkHttpClient(),
            rateLimiter = ArxivRateLimiter(minSpacingMs = 0),
            dispatchers = dispatchers,
            baseUrl = server.url("/api/query").toString(),
            retryDelaysMs = retryDelaysMs,
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

    @Test
    fun `successful response parses feed and sends contract headers`() =
        runTest {
            server.enqueue(MockResponse().setBody(fixtureXml()))

            val result = client().fetch(ArxivQuery.category("cs.LG"))

            val feed = assertIs<AppResult.Success<ArxivFeed>>(result).value
            assertEquals(2, feed.papers.size)

            val recorded = server.takeRequest()
            assertEquals("cat:cs.LG", recorded.requestUrl?.queryParameter("search_query"))
            assertEquals(ArxivApiClient.DEFAULT_USER_AGENT, recorded.getHeader("User-Agent"))
        }

    @Test
    fun `retries 503 then succeeds`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(fixtureXml()))

            val result = client().fetch(ArxivQuery.category("cs.LG"))

            assertIs<AppResult.Success<ArxivFeed>>(result)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `exhausted retries surface upstream error`() =
        runTest {
            repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }

            val result = client().fetch(ArxivQuery.category("cs.LG"))

            val failure = assertIs<AppResult.Failure>(result)
            assertEquals(AppError.Upstream(503), failure.error)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun `client error is not retried`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(400))

            val result = client().fetch(ArxivQuery.category("cs.LG"))

            val failure = assertIs<AppResult.Failure>(result)
            assertEquals(AppError.Upstream(400), failure.error)
            assertEquals(1, server.requestCount)
        }
}
