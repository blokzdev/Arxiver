package dev.blokz.arxiver.core.network.pdf

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PdfDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File

    // Real dispatchers: MockWebServer does real I/O (mirrors ArxivApiClientTest).
    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Default
        }

    /** Counts acquire() calls; zero spacing so tests don't actually wait. (ArxivRateLimiter is `open`.) */
    private class CountingRateLimiter : ArxivRateLimiter(minSpacingMs = 0) {
        var calls = 0
            private set

        override suspend fun acquire() {
            calls++
            super.acquire()
        }
    }

    // A plain (un-gated) client: MockWebServer serves http://127.0.0.1, which the AllowedHosts gate
    // would reject — so PdfDownloader tests must use a bare client (the gate is tested separately).
    private fun downloader(limiter: ArxivRateLimiter) = PdfDownloader(OkHttpClient(), limiter, dispatchers)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = Files.createTempDirectory("pdfdl").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    @Test
    fun `a cache hit issues zero requests and zero rate-limit acquires`() =
        runTest {
            val dest = File(dir, "cached.pdf").apply { writeText("already here") }
            val limiter = CountingRateLimiter()

            val result = downloader(limiter).download(server.url("/p.pdf").toString(), dest)

            assertIs<AppResult.Success<File>>(result)
            assertEquals(0, server.requestCount, "cache hit must not hit the network")
            assertEquals(0, limiter.calls, "cache hit must not claim a rate-limit slot")
        }

    @Test
    fun `a cache miss acquires the rate-limit slot exactly once and downloads`() =
        runTest {
            server.enqueue(MockResponse().setBody("%PDF-1.7 body"))
            val dest = File(dir, "fresh.pdf")
            val limiter = CountingRateLimiter()

            val result = downloader(limiter).download(server.url("/p.pdf").toString(), dest)

            assertIs<AppResult.Success<File>>(result)
            assertEquals(1, limiter.calls, "exactly one acquire on a cache miss")
            assertEquals(1, server.requestCount)
            assertTrue(dest.exists() && dest.readText().contains("%PDF"))
        }

    @Test
    fun `an upstream error maps to Upstream and a dropped connection to Offline`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))
            val r404 = downloader(CountingRateLimiter()).download(server.url("/p.pdf").toString(), File(dir, "a.pdf"))
            assertEquals(AppError.Upstream(404), assertIs<AppResult.Failure>(r404).error)

            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val rOff = downloader(CountingRateLimiter()).download(server.url("/p.pdf").toString(), File(dir, "b.pdf"))
            assertEquals(AppError.Offline, assertIs<AppResult.Failure>(rOff).error)
        }
}
