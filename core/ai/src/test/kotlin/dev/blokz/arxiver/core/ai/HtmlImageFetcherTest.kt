package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MockWebServer goldens for [HtmlImageFetcher] (PH.5): each fetch claims a rate-limit slot (the red
 * line); non-2xx / non-raster Content-Type / oversize / running-budget-exceeded omit the image (→
 * figcaption); successes emit via the callback (so a deadline keeps partials) and base64-encode.
 */
class HtmlImageFetcherTest {
    private lateinit var server: MockWebServer

    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Default
        }

    private class CountingLimiter : ArxivRateLimiter(minSpacingMs = 0) {
        var calls = 0
            private set

        override suspend fun acquire() {
            calls++
            super.acquire()
        }
    }

    private fun pngResponse(bytes: Int) =
        MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(ByteArray(bytes)))

    private fun fetcher(
        limiter: ArxivRateLimiter,
        maxImageBytes: Long = 1_000,
        totalBudget: Long = 1_000_000,
    ) = HtmlImageFetcher(
        OkHttpClient(),
        limiter,
        dispatchers,
        maxImageBytes = maxImageBytes,
        totalBytesBudget = totalBudget,
    )

    private fun images(n: Int) =
        (0 until n).map {
            ReaderImage(localKey = "k$it", fetchUrl = server.url("/img/$it").toString())
        }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetches serially, one acquire each, and base64-encodes the bytes`() =
        runTest {
            repeat(3) { server.enqueue(pngResponse(10)) }
            val limiter = CountingLimiter()
            val emitted = mutableListOf<String>()

            val out = fetcher(limiter).fetchAll(images(3)) { key, _ -> emitted.add(key) }

            assertEquals(3, out.size)
            assertEquals(3, limiter.calls, "one rate-limit slot per image")
            assertEquals(3, server.requestCount)
            assertEquals("png", out.getValue("k0").mimeSubtype)
            assertEquals(Base64.getEncoder().encodeToString(ByteArray(10)), out.getValue("k0").base64)
            assertEquals(listOf("k0", "k1", "k2"), emitted, "each success emits via the callback (partial-safe)")
        }

    @Test
    fun `a non-2xx image is omitted`() =
        runTest {
            server.enqueue(pngResponse(10))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(pngResponse(10))

            val out = fetcher(CountingLimiter()).fetchAll(images(3))

            assertEquals(setOf("k0", "k2"), out.keys)
        }

    @Test
    fun `an oversize image is omitted`() =
        runTest {
            server.enqueue(pngResponse(2_000)) // > maxImageBytes = 1000
            val out = fetcher(CountingLimiter(), maxImageBytes = 1_000).fetchAll(images(1))
            assertTrue(out.isEmpty())
        }

    @Test
    fun `the running byte budget truncates the remainder`() =
        runTest {
            // budget 2500, each image 1000 → after 3 images total 3000 >= budget → the 4th is never fetched.
            repeat(4) { server.enqueue(pngResponse(1_000)) }
            val limiter = CountingLimiter()

            val out = fetcher(limiter, maxImageBytes = 1_000, totalBudget = 2_500).fetchAll(images(4))

            assertEquals(3, out.size, "stops once the budget is crossed")
            assertEquals(3, limiter.calls, "no fetch is issued past the budget")
        }

    @Test
    fun `non-raster content types are omitted`() =
        runTest {
            server.enqueue(MockResponse().setHeader("Content-Type", "image/svg+xml").setBody("<svg/>"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html/>"))
            server.enqueue(pngResponse(10))

            val out = fetcher(CountingLimiter()).fetchAll(images(3))

            assertEquals(setOf("k2"), out.keys, "svg+xml and html rejected; only the png kept")
            assertFalse(out.containsKey("k0"))
        }
}
