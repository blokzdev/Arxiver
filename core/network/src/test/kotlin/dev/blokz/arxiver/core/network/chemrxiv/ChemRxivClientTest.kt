package dev.blokz.arxiver.core.network.chemrxiv

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `ChemRxivClient.searchItems` (P-Tools PT.4). MockWebServer, real dispatchers. The fixture mirrors the
 * Cambridge Open Engage `/items` envelope — the `itemHits[].item` nesting + item field names are
 * confirmed against a real Open Engage wrapper's parse code (mlederbauer/chemrxiv `from_api_response`),
 * NOT guessed; a live-GET confirmation still rides `VERIFICATION.md` (CI cannot reach the Cloudflare-gated
 * API). The highest-value assertion is that the `.item` wrapper is unwrapped (a flat DTO → zero hits).
 */
class ChemRxivClientTest {
    private lateinit var server: MockWebServer

    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Default
        }

    private fun client() =
        ChemRxivClient(
            httpClient = OkHttpClient(),
            dispatchers = dispatchers,
            baseUrl = server.url("").toString().removeSuffix("/"),
            minSpacingMs = 0,
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

    // `itemHits[].item` nesting; author `name` AND firstName/lastName; asset.original.url; a numeric-ish
    // extra key ("version") the DTO omits — must be ignored, not crash.
    private fun searchBody() =
        """
        { "totalCount": 2, "itemHits": [
          { "item": { "id": "aaa", "title": "A Catalyst", "abstract": "We report a catalyst.",
              "doi": "10.26434/chemrxiv-aaa", "publishedDate": "2024-03-01T00:00:00Z", "version": 2,
              "authors": [{"firstName":"Ada","lastName":"Lovelace"},{"name":"G. Hopper"}],
              "asset": {"original": {"url": "https://chemrxiv.org/aaa.pdf"}} } },
          { "item": { "id": "bbb", "title": "B Reaction", "abstract": "A reaction.",
              "doi": "10.26434/chemrxiv-bbb", "publishedDate": "2024-04-01T00:00:00Z",
              "pdfUrl": "https://chemrxiv.org/bbb.pdf", "authors": [] } }
        ] }
        """.trimIndent()

    @Test
    fun `searchItems unwraps the itemHits-dot-item envelope and both author shapes`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody(searchBody()))
            val r = client().searchItems("catalysis", limit = 10)
            assertIs<AppResult.Success<ChemRxivSearchResponse>>(r)
            val page = r.value
            assertEquals(2, page.totalCount)
            assertEquals(2, page.itemHits.size, "a flat data:[] DTO would yield 0 — the .item wrapper is unwrapped")
            val first = page.itemHits.first().item!!
            assertEquals("A Catalyst", first.title)
            assertEquals("10.26434/chemrxiv-aaa", first.doi)
            assertEquals("Ada", first.authors[0].firstName)
            assertEquals("G. Hopper", first.authors[1].name)
            assertEquals("https://chemrxiv.org/aaa.pdf", first.asset?.original?.url)
            assertEquals("https://chemrxiv.org/bbb.pdf", page.itemHits[1].item!!.pdfUrl)
        }

    @Test
    fun `the term and skip params are URL-encoded into the items path`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody(searchBody()))
            client().searchItems("gold nanoparticles", limit = 7, skip = 20)
            val path = server.takeRequest().path!!
            assertTrue(path.startsWith("/engage/chemrxiv/public-api/v1/items"), path)
            assertTrue(path.contains("term=gold%20nanoparticles"), path)
            assertTrue(path.contains("limit=7"), path)
            assertTrue(path.contains("skip=20"), path)
        }

    @Test
    fun `skip is omitted when zero`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody(searchBody()))
            client().searchItems("q", limit = 5, skip = 0)
            assertTrue(!server.takeRequest().path!!.contains("skip="))
        }

    @Test
    fun `a 429 is surfaced as Upstream(429)`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
            assertEquals(AppResult.Failure(AppError.Upstream(429)), client().searchItems("q", limit = 5))
        }

    @Test
    fun `the polite mutex enforces a REAL wall-clock wait between back-to-back calls`() =
        runBlocking {
            // Real clock (default nowMs) so the mutex must impose an ACTUAL delay, not just compute one.
            // Deleting `delay(wait)` from space() would drop elapsed to a few ms and fail this — where the
            // requestCount-only assertion would still pass. minSpacingMs kept small to keep the test fast.
            val spacingMs = 60L
            val c =
                ChemRxivClient(
                    httpClient = OkHttpClient(),
                    dispatchers = dispatchers,
                    baseUrl = server.url("").toString().removeSuffix("/"),
                    minSpacingMs = spacingMs,
                )
            server.enqueue(MockResponse().setResponseCode(200).setBody(searchBody()))
            server.enqueue(MockResponse().setResponseCode(200).setBody(searchBody()))
            val elapsed =
                measureTimeMillis {
                    c.searchItems("q", limit = 5) // no spacing on the first call
                    c.searchItems("q", limit = 5) // must wait ≥ spacingMs behind the first
                }
            assertEquals(2, server.requestCount)
            // Two un-spaced MockWebServer calls take a few ms; the mutex's delay(spacingMs) is a hard floor.
            // Generous lower bound tolerates scheduling jitter while still failing hard if spacing is removed.
            val floor = spacingMs - 15
            assertTrue(elapsed >= floor, "expected >= ${floor}ms of enforced spacing, was ${elapsed}ms")
        }
}
