package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HtmlFetcherTest {
    private lateinit var server: MockWebServer
    private val id = ArxivId("2412.19437")

    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Default
        }

    /** Counts acquire() calls; zero spacing. (ArxivRateLimiter is `open`.) */
    private class CountingLimiter : ArxivRateLimiter(minSpacingMs = 0) {
        var calls = 0
            private set

        override suspend fun acquire() {
            calls++
            super.acquire()
        }
    }

    private val okHtml =
        """<html><body><article class="ltx_document"><h1>T</h1><p>Hello.</p>""" +
            """<ul class="ltx_biblist"><li id="bib.bib1">Ref</li></ul></article></body></html>"""

    private val degradedHtml =
        """<html><body><article class="ltx_document"><p>""" +
            """<a class="ltx_ref" href="#bib.bib1">[1]</a><a class="ltx_ref" href="#bib.bib2">[2]</a>""" +
            """<a class="ltx_ref" href="#bib.bib3">[3]</a></p></article></body></html>"""

    /** A client that rewrites every arxiv/ar5iv request to the MockWebServer, preserving path. */
    private fun fetcher(limiter: ArxivRateLimiter): HtmlFetcher {
        val rewrite =
            Interceptor { chain ->
                val rewritten =
                    chain.request().url.newBuilder()
                        .scheme("http").host(server.hostName).port(server.port).build()
                chain.proceed(chain.request().newBuilder().url(rewritten).build())
            }
        return HtmlFetcher(OkHttpClient.Builder().addInterceptor(rewrite).build(), limiter, dispatchers)
    }

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
    fun `native 200 OK resolves to Native with one rate-limit acquire`() =
        runTest {
            server.enqueue(MockResponse().setBody(okHtml))
            val limiter = CountingLimiter()

            val result = fetcher(limiter).fetch(id, version = 2)

            assertIs<HtmlFetchResult.Native>(result)
            assertEquals(Fidelity.OK, result.doc.fidelity.fidelity)
            assertEquals(1, limiter.calls)
            assertEquals(1, server.requestCount)
            val path = server.takeRequest().path
            assertEquals(true, path?.contains("/html/2412.19437v2"), "versioned native path: $path")
        }

    @Test
    fun `native 404 retries the bare native (latest), then resolves Native — two acquires`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody(okHtml))
            val limiter = CountingLimiter()

            val result = fetcher(limiter).fetch(id, version = 2)

            assertIs<HtmlFetchResult.Native>(result)
            assertEquals(2, limiter.calls)
            assertEquals(2, server.requestCount)
            assertEquals(true, server.takeRequest().path?.contains("/html/2412.19437v2"))
            val bare = server.takeRequest().path
            assertEquals(true, bare?.endsWith("/html/2412.19437"), "bare native (no version): $bare")
        }

    @Test
    fun `a DEGRADED native falls through to ar5iv`() =
        runTest {
            server.enqueue(MockResponse().setBody(degradedHtml)) // native versioned 200 but degraded
            server.enqueue(MockResponse().setBody(okHtml)) // ar5iv clean
            val limiter = CountingLimiter()

            val result = fetcher(limiter).fetch(id, version = 1)

            assertIs<HtmlFetchResult.Ar5iv>(result)
            assertEquals(2, limiter.calls, "native(versioned) + ar5iv; no bare retry on a 200")
        }

    @Test
    fun `all sources 404 falls back to PDF — three acquires`() =
        runTest {
            repeat(3) { server.enqueue(MockResponse().setResponseCode(404)) }
            val limiter = CountingLimiter()

            val result = fetcher(limiter).fetch(id, version = 2)

            assertEquals(HtmlFetchResult.FallbackToPdf, result)
            assertEquals(3, limiter.calls, "native versioned + native bare + ar5iv")
        }

    @Test
    fun `a dropped connection surfaces Offline`() =
        runTest {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val limiter = CountingLimiter()

            val result = fetcher(limiter).fetch(id, version = 2)

            assertEquals(AppError.Offline, assertIs<HtmlFetchResult.Error>(result).error)
            assertEquals(1, limiter.calls)
        }
}
