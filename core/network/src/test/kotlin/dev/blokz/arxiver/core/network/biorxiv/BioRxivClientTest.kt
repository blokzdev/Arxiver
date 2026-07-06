package dev.blokz.arxiver.core.network.biorxiv

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.network.PreprintPage
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BioRxivClientTest {
    private lateinit var server: MockWebServer

    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Default
        }

    private fun client() =
        BioRxivApiClient(
            OkHttpClient(),
            dispatchers,
            baseUrl = server.url("").toString().removeSuffix("/"),
            minSpacingMs = 0,
        )

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("biorxiv/$name")!!.bufferedReader().readText()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `decodes the real details fixture (string total, semicolon authors)`() =
        runTest {
            server.enqueue(MockResponse().setBody(fixture("biorxiv_details.json")))
            val r = client().details("biorxiv", "2026-06-01", "2026-06-07", 0, "neuroscience")
            val resp = assertIs<AppResult.Success<BioRxivResponse>>(r).value
            assertEquals("263", resp.messages[0].total, "total is a STRING in the API")
            assertEquals(30, resp.messages[0].count)
            assertEquals(2, resp.collection.size)
            val item = resp.collection[0]
            assertEquals("10.1101/2025.10.07.680948", item.doi)
            assertEquals("3", item.version)
            assertTrue(item.authorList().size >= 3, "semicolon author string is split")
        }

    @Test
    fun `details builds the path segments and the category query param`() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"messages":[],"collection":[]}"""))
            client().details("medrxiv", "2026-01-01", "2026-01-31", 30, "psychiatry")
            val url = server.takeRequest().requestUrl!!
            assertEquals("/details/medrxiv/2026-01-01/2026-01-31/30/json", url.encodedPath)
            assertEquals("psychiatry", url.queryParameter("category"))
        }

    @Test
    fun `no category param when null or blank`() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"messages":[],"collection":[]}"""))
            client().details("biorxiv", "a", "b", 0, null)
            assertNull(server.takeRequest().requestUrl!!.queryParameter("category"))
        }

    @Test
    fun `error mapping — non-2xx to Upstream, dropped connection to Offline`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(429))
            assertEquals(
                AppError.Upstream(429),
                assertIs<AppResult.Failure>(client().details("biorxiv", "a", "b", 0, null)).error,
            )
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            assertEquals(
                AppError.Offline,
                assertIs<AppResult.Failure>(client().details("biorxiv", "a", "b", 0, null)).error,
            )
        }

    // --- BioRxivBackend ---

    @Test
    fun `backend maps items to hits with a synthesized allowlisted PDF url, and advances the cursor`() =
        runTest {
            server.enqueue(MockResponse().setBody(fixture("biorxiv_details.json")))
            val backend = BioRxivBackend(client(), today = { "2026-06-07" })
            val page =
                assertIs<AppResult.Success<PreprintPage>>(
                    backend.browse(Source.BIORXIV, "neuroscience", "2026-06-01", cursor = null),
                ).value
            assertEquals(2, page.hits.size)
            val h = page.hits[0]
            assertEquals(Source.BIORXIV, h.origin)
            assertEquals("10.1101/2025.10.07.680948", h.doi)
            // deterministic bioRxiv PDF (host-allowlisted), version from `details`:
            assertEquals("https://www.biorxiv.org/content/10.1101/2025.10.07.680948v3.full.pdf", h.oaPdfUrl)
            assertTrue(h.authors.isNotEmpty())
            assertTrue(h.abstract.isNotBlank())
            // count=30, total=263, offset 0 → next page offset 30.
            assertEquals("30", page.nextCursor)
        }

    @Test
    fun `backend browses the given server from sinceIso to today`() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"messages":[{"total":"0","count":0}],"collection":[]}"""))
            val backend = BioRxivBackend(client(), today = { "2026-07-06" })
            backend.browse(Source.MEDRXIV, category = null, sinceIso = "2026-07-01", cursor = "60")
            val url = server.takeRequest().requestUrl!!
            assertEquals(
                "/details/medrxiv/2026-07-01/2026-07-06/60/json",
                url.encodedPath,
                "server, since, today, cursor",
            )
        }
}
