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

    // --- recommendationsForPaper (P-Discover-MLT PDM.1) ---

    // The documented /recommendations/v1/papers/forpaper envelope: `recommendedPapers` of the SAME
    // paper shape as search (fields param reuses SEARCH_FIELDS) — re-confirm against a live GET
    // (VERIFICATION §PDM) before trusting field names.
    private fun recommendationsBody() =
        """
        { "recommendedPapers": [
          { "paperId": "r1", "title": "A very similar paper", "abstract": "Similar things.",
            "externalIds": {"ArXiv":"2606.11111","CorpusId":1}, "venue": "", "year": 2026,
            "authors": [{"authorId":"9","name":"C. Lee"}], "citationCount": 3 },
          { "paperId": "r2", "title": "A DOI-only recommendation",
            "externalIds": {"DOI":"10.1101/2026.06.01.123456","CorpusId":2}, "year": 2026 }
        ] }
        """.trimIndent()

    @Test
    fun `recommendationsForPaper hits the recommendations router with an arXiv seed`() =
        runTest {
            enqueue(recommendationsBody())
            val r = client().recommendationsForPaper("ARXIV:1706.03762", limit = 30)
            assertIs<AppResult.Success<S2RecommendationsResponse>>(r)
            assertEquals(2, r.value.recommendedPapers.size)
            assertEquals("2606.11111", r.value.recommendedPapers[0].externalIds?.ArXiv)
            val path = server.takeRequest().path!!
            // The recommendations API is NOT under /graph/v1 — a copy-paste of the search base path
            // would 404 in production while passing any envelope-only test. (`:` is a legal pchar and
            // stays literal in the segment.)
            assertTrue(path.startsWith("/recommendations/v1/papers/forpaper/ARXIV:1706.03762"), path)
            assertTrue(path.contains("limit=30"), path)
            assertTrue(path.contains("fields="), path)
            // The recommendations router 400s on `tldr` (verified live 2026-07-17) — it must NOT be requested
            // here even though the graph-search sibling accepts it. This pin guards a copy-paste regression.
            assertTrue(!path.contains("tldr"), "recommendations must not request the unsupported tldr field: $path")
        }

    @Test
    fun `a DOI seed is one ENCODED path segment - its slash cannot inject extra segments`() =
        runTest {
            enqueue(recommendationsBody())
            client().recommendationsForPaper("DOI:10.21203/rs.3.rs-27656/v1", limit = 10)
            val path = server.takeRequest().path!!
            // HttpUrl.addPathSegment percent-encodes the DOI's slashes: the seed stays ONE segment.
            // Raw interpolation (the paperByArxivId style) would produce .../forpaper/DOI:10.21203/rs.3…
            // — extra segments and a broken route. (`:` is a legal pchar and stays literal.)
            assertTrue(path.contains("/forpaper/DOI:10.21203%2Frs.3.rs-27656%2Fv1"), path)
        }

    @Test
    fun `an empty recommendations envelope decodes to an empty list, not a crash`() =
        runTest {
            enqueue("""{ "recommendedPapers": [] }""")
            val r = client().recommendationsForPaper("ARXIV:2606.00001", limit = 30)
            assertIs<AppResult.Success<S2RecommendationsResponse>>(r)
            assertTrue(r.value.recommendedPapers.isEmpty())
        }

    @Test
    fun `an unindexed seed 404 surfaces as Upstream(404) - distinct from empty`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Paper not found"}"""))
            val r = client().recommendationsForPaper("DOI:10.9999/unknown", limit = 30)
            assertEquals(AppResult.Failure(AppError.Upstream(404)), r)
        }

    @Test
    fun `recommendations 429 surfaces as Upstream(429) - the keyless anonymous-pool case`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
            val r = client().recommendationsForPaper("ARXIV:2606.00001", limit = 30)
            assertEquals(AppResult.Failure(AppError.Upstream(429)), r)
        }
}
