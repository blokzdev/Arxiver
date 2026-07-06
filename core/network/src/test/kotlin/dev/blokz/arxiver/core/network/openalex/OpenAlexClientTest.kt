package dev.blokz.arxiver.core.network.openalex

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
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

class OpenAlexClientTest {
    private lateinit var server: MockWebServer

    // Real dispatchers: MockWebServer does real I/O (mirrors ChemRxiv/S2 client tests). A BARE OkHttpClient —
    // 127.0.0.1 is not allowlisted, so the client under test must not self-gate (the gate is the @ArxivClient
    // interceptor, tested separately).
    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Default
        }

    private fun client(apiKey: () -> String? = { null }) =
        OpenAlexClient(
            OkHttpClient(),
            dispatchers,
            mailto = "t@example.com",
            apiKey = apiKey,
            baseUrl = server.url("").toString().removeSuffix("/"),
            minSpacingMs = 0,
        )

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("openalex/$name")!!.bufferedReader().readText()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `decodes the real chemRxiv search fixture (URL-prefix strip + inverted-index abstract)`() =
        runTest {
            server.enqueue(MockResponse().setBody(fixture("chemrxiv_search.json")))
            val r = client().search("catalysis", 2, sourceId = OpenAlexClient.SID_CHEMRXIV)
            val works = assertIs<AppResult.Success<OpenAlexResponse>>(r).value.results
            assertEquals(2, works.size)
            val w = works[0]
            assertEquals("10.26434/chemrxiv-2024-9lpb9", w.bareDoi(), "doi prefix stripped")
            assertEquals("S4393918830", w.sourceId(), "source id prefix stripped")
            assertTrue(w.title!!.contains("Digital Catalysis"))
            assertTrue(w.authorNames().isNotEmpty(), "authorships → display names")
            assertTrue((w.abstractText() ?: "").length > 20, "abstract reconstructed from the inverted index")
        }

    @Test
    fun `search sends query, per-page, source filter, and mailto (no api_key when unset)`() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"meta":{"count":0},"results":[]}"""))
            client().search("graphene", 5, sourceId = "S123")
            val url = server.takeRequest().requestUrl!!
            assertEquals("graphene", url.queryParameter("search"))
            assertEquals("5", url.queryParameter("per-page"))
            assertEquals("primary_location.source.id:S123", url.queryParameter("filter"))
            assertEquals("t@example.com", url.queryParameter("mailto"))
            assertNull(url.queryParameter("api_key"))
        }

    @Test
    fun `browse sends the follow filter, date-desc sort, and cursor, and reads next_cursor`() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"meta":{"count":9,"next_cursor":"abc"},"results":[]}"""))
            val r = client().browse("S4306402567", "2026-06-01", cursor = "*", perPage = 30)
            val url = server.takeRequest().requestUrl!!
            assertEquals(
                "primary_location.source.id:S4306402567,from_publication_date:2026-06-01",
                url.queryParameter("filter"),
            )
            assertEquals("publication_date:desc", url.queryParameter("sort"))
            assertEquals("*", url.queryParameter("cursor"))
            assertEquals("30", url.queryParameter("per-page"))
            assertEquals("abc", assertIs<AppResult.Success<OpenAlexResponse>>(r).value.meta.nextCursor)
        }

    @Test
    fun `a non-blank category appends the OpenAlex Field clause, blank stays 2-clause (PF3)`() =
        runTest {
            // Field clause appended (live-verified grammar primary_topic.field.id:fields/N, Chemistry=16).
            server.enqueue(MockResponse().setBody("""{"meta":{"count":0},"results":[]}"""))
            client().browse("S4393918830", "2026-06-01", cursor = "*", category = "fields/16")
            assertEquals(
                "primary_location.source.id:S4393918830,from_publication_date:2026-06-01," +
                    "primary_topic.field.id:fields/16",
                server.takeRequest().requestUrl!!.queryParameter("filter"),
            )

            // A blank category must NOT append the clause — the whole-source filter stays byte-identical.
            server.enqueue(MockResponse().setBody("""{"meta":{"count":0},"results":[]}"""))
            client().browse("S4393918830", "2026-06-01", cursor = "*", category = "")
            assertEquals(
                "primary_location.source.id:S4393918830,from_publication_date:2026-06-01",
                server.takeRequest().requestUrl!!.queryParameter("filter"),
            )
        }

    @Test
    fun `an optional BYOK key is sent as api_key`() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"results":[]}"""))
            client(apiKey = { "SECRET" }).search("x", 1)
            assertEquals("SECRET", server.takeRequest().requestUrl!!.queryParameter("api_key"))
        }

    @Test
    fun `error mapping — non-2xx to Upstream, dropped connection to Offline`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(429))
            assertEquals(AppError.Upstream(429), assertIs<AppResult.Failure>(client().search("x", 1)).error)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            assertEquals(AppError.Offline, assertIs<AppResult.Failure>(client().search("x", 1)).error)
        }

    @Test
    fun `abstract inverted index reconstructs word order (incl a repeated word)`() {
        val w =
            OpenAlexWork(
                abstractInvertedIndex = mapOf("Hello" to listOf(0), "world" to listOf(1, 3), "big" to listOf(2)),
            )
        assertEquals("Hello world big world", w.abstractText())
    }

    @Test
    fun `an absent abstract index yields null, not an empty string`() {
        assertNull(OpenAlexWork(abstractInvertedIndex = null).abstractText())
        assertNull(OpenAlexWork(abstractInvertedIndex = emptyMap()).abstractText())
    }
}
