package dev.blokz.arxiver.core.network.s2

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `SemanticScholarClient.searchPapers` + the optional BYOK key (P-Tools PT.3). MockWebServer, real
 * dispatchers (real I/O). The search fixture mirrors the DOCUMENTED `/graph/v1/paper/search` envelope
 * (`tldr` object, `externalIds` incl. a numeric `CorpusId`, `next` present/absent) — **re-confirm
 * against a live GET before trusting field names** (VERIFICATION §Q-PT). `Json { ignoreUnknownKeys }`
 * de-risks extra fields, not renamed ones.
 */
class SemanticScholarClientTest {
    private lateinit var server: MockWebServer

    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Default
        }

    private fun client(apiKey: () -> String? = { null }) =
        SemanticScholarClient(
            httpClient = OkHttpClient(),
            dispatchers = dispatchers,
            baseUrl = server.url("").toString().removeSuffix("/"),
            apiKey = apiKey,
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

    private fun enqueue(body: String) = server.enqueue(MockResponse().setResponseCode(200).setBody(body))

    // A full search envelope: tldr OBJECT, externalIds with a NUMERIC CorpusId (must be dropped, not
    // crash), openAccessPdf, authors, and `next` present. A second element with no arXiv id.
    private fun searchBody() =
        """
        { "total": 2, "offset": 0, "next": 10, "data": [
          { "paperId": "abc", "title": "Attention Is All You Need", "abstract": "We propose the Transformer.",
            "tldr": {"model":"tldr@v2","text":"A new attention-only architecture."},
            "openAccessPdf": {"url":"https://arxiv.org/pdf/1706.03762","status":"GREEN"},
            "externalIds": {"ArXiv":"1706.03762","DOI":"10.48550/arXiv.1706.03762","PubMed":"123","CorpusId":13756489},
            "venue": "NeurIPS", "year": 2017, "citationCount": 100000,
            "authors": [{"authorId":"1","name":"A. Vaswani"},{"authorId":"2","name":"N. Shazeer"}] },
          { "paperId": "def", "title": "A non-arXiv paper", "externalIds": {"DOI":"10.1/x","CorpusId":999} }
        ] }
        """.trimIndent()

    @Test
    fun `searchPapers parses the full envelope incl tldr object and drops numeric CorpusId`() =
        runTest {
            enqueue(searchBody())
            val r = client().searchPapers("transformer", limit = 10)
            assertIs<AppResult.Success<S2SearchResponse>>(r)
            val page = r.value
            assertEquals(2, page.total)
            assertEquals(10, page.next)
            val first = page.data.first()
            assertEquals("Attention Is All You Need", first.title)
            assertEquals("A new attention-only architecture.", first.tldr?.text)
            assertEquals("1706.03762", first.externalIds?.ArXiv)
            assertEquals("123", first.externalIds?.PubMed)
            assertEquals("https://arxiv.org/pdf/1706.03762", first.openAccessPdf?.url)
            assertEquals(listOf("A. Vaswani", "N. Shazeer"), first.authors.mapNotNull { it.name })
            // The numeric CorpusId is absent from the DTO and must NOT crash the parse.
            assertNull(page.data[1].externalIds?.ArXiv)
        }

    @Test
    fun `next is null on the last page`() =
        runTest {
            enqueue("""{ "total": 1, "offset": 0, "data": [] }""")
            val r = client().searchPapers("q", limit = 5)
            assertIs<AppResult.Success<S2SearchResponse>>(r)
            assertNull(r.value.next)
        }

    @Test
    fun `a BYOK key is sent as x-api-key when set`() =
        runTest {
            enqueue(searchBody())
            client(apiKey = { "secret-key" }).searchPapers("q", limit = 5)
            assertEquals("secret-key", server.takeRequest().getHeader("x-api-key"))
        }

    @Test
    fun `no x-api-key header is sent when the key is unset (free tier)`() =
        runTest {
            enqueue(searchBody())
            client(apiKey = { null }).searchPapers("q", limit = 5)
            assertNull(server.takeRequest().getHeader("x-api-key"))
        }

    @Test
    fun `the key supplier is evaluated per-request, honoring a key entered after construction`() =
        runTest {
            // The single sharpest test: a captured-value ctor would send null on BOTH calls (the key
            // was unset when the singleton was built). The supplier must read the LATEST value.
            var key: String? = null
            val c = client(apiKey = { key })
            enqueue(searchBody())
            c.searchPapers("q", limit = 5)
            assertNull(server.takeRequest().getHeader("x-api-key"), "no key yet → no header")
            key = "entered-later"
            enqueue(searchBody())
            c.searchPapers("q", limit = 5)
            assertEquals("entered-later", server.takeRequest().getHeader("x-api-key"), "later key IS sent")
        }

    @Test
    fun `the query and year window are URL-encoded into the request`() =
        runTest {
            enqueue(searchBody())
            client().searchPapers("graph neural nets", limit = 7, venue = "ICML", yearFrom = 2019, yearTo = 2023)
            val path = server.takeRequest().path!!
            assertTrue(path.startsWith("/graph/v1/paper/search"), path)
            assertTrue(path.contains("query=graph%20neural%20nets"), path)
            assertTrue(path.contains("limit=7"), path)
            assertTrue(path.contains("venue=ICML"), path)
            assertTrue(path.contains("year=2019-2023"), path)
        }

    @Test
    fun `a 429 is surfaced as Upstream(429), not thrown or swallowed`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
            val r = client().searchPapers("q", limit = 5)
            assertEquals(AppResult.Failure(AppError.Upstream(429)), r)
        }

    @Test
    fun `yearFilter renders open-ended and closed windows`() {
        assertEquals("2019-2023", SemanticScholarClient.yearFilter(2019, 2023))
        assertEquals("2019-", SemanticScholarClient.yearFilter(2019, null))
        assertEquals("-2023", SemanticScholarClient.yearFilter(null, 2023))
        assertNull(SemanticScholarClient.yearFilter(null, null))
    }
}
