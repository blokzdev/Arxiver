package dev.blokz.arxiver.core.network.openalex

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
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpenAlexBackendTest {
    private lateinit var server: MockWebServer

    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Default
        }

    private fun backend() =
        OpenAlexBackend(
            OpenAlexClient(
                OkHttpClient(),
                dispatchers,
                mailto = "t@example.com",
                baseUrl = server.url("").toString().removeSuffix("/"),
                minSpacingMs = 0,
            ),
        ) { s -> if (s == Source.CHEMRXIV) OpenAlexClient.SID_CHEMRXIV else null }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `browse maps works to hits, sends the source + date filter + cursor, reads next_cursor`() =
        runTest {
            server.enqueue(
                MockResponse().setBody(
                    """{"meta":{"count":1,"next_cursor":"NEXTABC"},"results":[
                    {"id":"https://openalex.org/W1","doi":"https://doi.org/10.26434/x",
                     "title":"A chem paper","publication_date":"2026-02-03",
                     "abstract_inverted_index":{"hello":[0],"world":[1]},
                     "authorships":[{"author":{"display_name":"Ada Lovelace"}}],
                     "primary_location":{"source":{"id":"https://openalex.org/S4393918830"}},
                     "best_oa_location":{"pdf_url":"https://chemrxiv.org/x.pdf"}}]}""",
                ),
            )
            val page =
                assertIs<AppResult.Success<PreprintPage>>(
                    backend().browse(Source.CHEMRXIV, category = null, sinceIso = "2026-01-01", cursor = null),
                ).value
            assertEquals(1, page.hits.size)
            val h = page.hits[0]
            assertEquals(Source.CHEMRXIV, h.origin)
            assertEquals("10.26434/x", h.doi)
            assertEquals("hello world", h.abstract)
            assertEquals("https://chemrxiv.org/x.pdf", h.oaPdfUrl)
            assertEquals(listOf("Ada Lovelace"), h.authors)
            assertEquals("NEXTABC", page.nextCursor)

            val url = server.takeRequest().requestUrl!!
            val filter = url.queryParameter("filter")!!
            assert(filter.contains("primary_location.source.id:S4393918830")) { filter }
            assert(filter.contains("from_publication_date:2026-01-01")) { filter }
            assertEquals("*", url.queryParameter("cursor"))
        }

    @Test
    fun `an unmapped source fails (no OpenAlex source id)`() =
        runTest {
            // BIORXIV is served natively, not via OpenAlex — sidFor returns null.
            assertIs<AppResult.Failure>(
                backend().browse(Source.BIORXIV, category = null, sinceIso = "2026-01-01", cursor = null),
            )
        }
}
