package dev.blokz.arxiver.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.ExternalPaperDraft
import dev.blokz.arxiver.core.model.ExternalRef
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
import dev.blokz.arxiver.core.network.openalex.OpenAlexClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the IMPORT-seam cross-source de-dup (P-Explorer PE.2). PFP.1 fixed the *follow* path
 * (`FollowSyncWorker.canonicalRef`) but `saveExternalPaper` never consulted `paperIdByDoi`, so importing a paper
 * already stored under another origin forked it — the exact defect PFP.1 existed to prevent.
 *
 * The MockWebServer is never hit: `saveExternalPaper` is a pure-local, zero-network path.
 */

@RunWith(RobolectricTestRunner::class)
class PaperRepositoryTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var server: MockWebServer
    private lateinit var repo: PaperRepository

    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Default
        }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        server = MockWebServer().apply { start() }
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        val client =
            ArxivApiClient(
                httpClient = OkHttpClient(),
                rateLimiter = ArxivRateLimiter(minSpacingMs = 0),
                dispatchers = dispatchers,
                baseUrl = server.url("/api/query").toString(),
                retryDelaysMs = emptyList(),
            )
        repo = PaperRepository(client, db.paperDao(), testOpenAlexClient(server), dispatchers)
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private suspend fun seed(p: Paper) = db.paperDao().upsertPaperWithRelations(p.toEntity(), p.authors, p.categories)

    private fun draft(nativeId: String) =
        ExternalPaperDraft(
            origin = Source.CHEMRXIV,
            nativeId = nativeId,
            title = "Imported chemRxiv edition",
            abstract = "a",
            authors = listOf("A"),
            publishedAt = Instant.EPOCH,
            pdfUrl = "https://chemrxiv.org/x.pdf",
        )

    @Test
    fun `importing a paper already stored under another origin reuses it — no fork`() =
        runBlocking {
            seed(
                Paper(
                    ref = ArxivRef(ArxivId("2403.09999")), latestVersion = 2, title = "Rich arXiv Title",
                    abstract = "a", publishedAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
                    primaryCategory = "cs.LG", categories = listOf("cs.LG"), authors = listOf("A"),
                    doi = "10.26434/shared-doi", pdfUrl = "https://arxiv.org/pdf/2403.09999v2",
                ),
            )

            val imported = repo.saveExternalPaper(draft("10.26434/shared-doi"))

            assertEquals(1, db.paperDao().count(), "the import must not fork an existing same-DOI row")
            assertEquals("2403.09999", imported.ref.storageId, "the stored arXiv row wins (arXiv-origin preferred)")
            // The richer native row is NOT clobbered by the import's thinner metadata.
            assertEquals("Rich arXiv Title", imported.title)
            assertEquals("cs.LG", imported.primaryCategory)
        }

    @Test
    fun `importing a paper whose stored DOI is VERSIONED still reuses it (doi_norm)`() =
        runBlocking {
            seed(
                Paper(
                    ref = ExternalRef(Source.BIORXIV, "10.1101/zed.v3"), latestVersion = 1, title = "Bio",
                    abstract = "a", publishedAt = Instant.EPOCH, updatedAt = Instant.EPOCH, primaryCategory = "",
                    categories = emptyList(), authors = listOf("A"), doi = "10.1101/zed.v3", pdfUrl = "",
                ),
            )

            val imported = repo.saveExternalPaper(draft("10.1101/zed"))

            assertEquals(1, db.paperDao().count())
            assertEquals("biorxiv:10.1101/zed.v3", imported.ref.storageId)
        }

    @Test
    fun `importing a genuinely new paper still creates it`() =
        runBlocking {
            val imported = repo.saveExternalPaper(draft("10.26434/brand-new"))

            assertEquals(1, db.paperDao().count())
            assertEquals("chemrxiv:10.26434/brand-new", imported.ref.storageId)
            assertEquals("Imported chemRxiv edition", imported.title)
        }
    // --- P-Explorer PE.3: external search + persist-on-interaction ---

    private fun enqueueGolden() {
        val body =
            checkNotNull(
                Thread.currentThread().contextClassLoader?.getResource("openalex/chemrxiv_search.json"),
            ).readText()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(body).setHeader("Content-Type", "application/json"))
    }

    @Test
    fun `searchExternal maps the golden page and never caches eagerly`() =
        runBlocking {
            enqueueGolden()

            val page = (repo.searchExternal(Source.CHEMRXIV, "catalysis") as AppResult.Success).value

            assertEquals(2, page.papers.size)
            assertEquals(12805, page.totalResults)
            assertNull(page.nextStart, "un-paginated v1: a scroll can never bill a BYOK key")
            assertEquals(0, db.paperDao().count(), "search results persist on interaction, not on render")
            assertEquals("Materials Science", page.papers.first().primaryCategory)
        }

    @Test
    fun `cacheSearchHit persists a new hit and returns it`() =
        runBlocking {
            enqueueGolden()
            val hit = (repo.searchExternal(Source.CHEMRXIV, "catalysis") as AppResult.Success).value.papers.first()

            val stored = repo.cacheSearchHit(hit)

            assertEquals(hit.ref.storageId, stored.ref.storageId)
            assertEquals(1, db.paperDao().count())
            // Idempotent: a second interaction is a no-op.
            repo.cacheSearchHit(hit)
            assertEquals(1, db.paperDao().count())
        }

    @Test
    fun `cacheSearchHit reuses an existing same-DOI row and does not clobber it`() =
        runBlocking {
            seed(
                Paper(
                    ref = ArxivRef(ArxivId("2403.09999")), latestVersion = 2, title = "Rich arXiv Title",
                    abstract = "a", publishedAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
                    primaryCategory = "cs.LG", categories = listOf("cs.LG"), authors = listOf("A"),
                    doi = "10.26434/chemrxiv-2024-9lpb9", pdfUrl = "https://arxiv.org/pdf/2403.09999v2",
                ),
            )
            enqueueGolden()
            val hit = (repo.searchExternal(Source.CHEMRXIV, "catalysis") as AppResult.Success).value.papers.first()

            val stored = repo.cacheSearchHit(hit)

            assertEquals("2403.09999", stored.ref.storageId, "the winning id is the stored row's, not the hit's")
            assertEquals("Rich arXiv Title", stored.title, "the richer stored row is not clobbered")
            assertEquals(1, db.paperDao().count())
        }

    // --- P-OA: open-access published-version resolver ---

    private fun rsPaper(
        title: String = OA_TITLE,
        authors: List<String> = listOf("Ahmed Abdelfattah"),
    ) = Paper(
        ref = ExternalRef(Source.RESEARCH_SQUARE, "10.21203/rs.3.rs-27656/v1"),
        latestVersion = 1, title = title, abstract = "", publishedAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        primaryCategory = "", categories = emptyList(), authors = authors, doi = "10.21203/rs.3.rs-27656/v1",
        pdfUrl = "https://www.researchsquare.com/article/rs-27656/v1.pdf",
    )

    private fun plainPaper(
        ref: dev.blokz.arxiver.core.model.PaperRef,
        title: String,
        doi: String? = null,
    ) = Paper(
        ref = ref, latestVersion = 1, title = title, abstract = "", publishedAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH, primaryCategory = "", categories = emptyList(), authors = listOf("A"), doi = doi,
    )

    @Test
    fun `resolveOaFulltext is a zero-call no-op for arXiv, IN_APP, and blank-title papers`() =
        runBlocking {
            assertEquals(OaResult.None, repo.resolveOaFulltext(plainPaper(ArxivRef(ArxivId("2403.09999")), "T")))
            assertEquals(
                OaResult.None,
                repo.resolveOaFulltext(plainPaper(ExternalRef(Source.BIORXIV, "10.1101/x"), "T", "10.1101/x")),
            )
            assertEquals(OaResult.None, repo.resolveOaFulltext(rsPaper(title = "")))
            assertEquals(0, server.requestCount, "eligibility short-circuits before any OpenAlex call")
        }

    @Test
    fun `resolveOaFulltext finds the published OA sibling in exactly one host-free call`() =
        runBlocking {
            server.enqueue(
                okhttp3.mockwebserver.MockResponse().setBody(OA_CROSSWALK_JSON)
                    .setHeader("Content-Type", "application/json"),
            )

            val found = assertIs<OaResult.Found>(repo.resolveOaFulltext(rsPaper()))

            assertEquals(
                "https://sfamjournals.onlinelibrary.wiley.com/doi/pdfdirect/10.1111/1462-2920.15392",
                found.pdfUrl,
            )
            assertEquals("Environmental Microbiology", found.journalName)
            assertTrue(found.versionOfRecord)
            val req = server.takeRequest().requestUrl!!
            assertEquals(OA_TITLE, req.queryParameter("search"))
            assertNull(req.queryParameter("filter"), "sourceId=null → all-OpenAlex crosswalk, no source filter")
            assertEquals(1, server.requestCount, "exactly one OpenAlex call per explicit resolve")
        }

    @Test
    fun `resolveOaFulltext maps a transient failure to a retryable Error, never a false None`() =
        runBlocking {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(429))
            assertTrue(repo.resolveOaFulltext(rsPaper()) is OaResult.Error)
        }
}

private const val OA_TITLE =
    "Experimental evidence of microbial inheritance in plants and transmission routes " +
        "from seed to phyllosphere and root"

/** A minimal live-shaped OpenAlex crosswalk: the published Wiley article + the Research-Square preprint sibling. */
private val OA_CROSSWALK_JSON =
    """
    {
      "meta": {"count": 2},
      "results": [
        {
          "id": "https://openalex.org/W2000000001",
          "doi": "https://doi.org/10.1111/1462-2920.15392",
          "title": "$OA_TITLE",
          "type": "article",
          "cited_by_count": 216,
          "authorships": [{"author": {"display_name": "Ahmed Abdelfattah"}}],
          "primary_location": {"source": {"display_name": "Environmental Microbiology", "type": "journal"}},
          "best_oa_location": {
            "pdf_url": "https://sfamjournals.onlinelibrary.wiley.com/doi/pdfdirect/10.1111/1462-2920.15392",
            "version": "publishedVersion", "is_oa": true,
            "source": {"display_name": "Environmental Microbiology"}
          },
          "open_access": {"is_oa": true, "oa_status": "hybrid"}
        },
        {
          "id": "https://openalex.org/W2000000002",
          "doi": "https://doi.org/10.21203/rs.3.rs-27656/v1",
          "title": "$OA_TITLE",
          "type": "preprint",
          "cited_by_count": 10,
          "authorships": [{"author": {"display_name": "Ahmed Abdelfattah"}}],
          "primary_location": {"source": {"display_name": "Research Square", "type": "repository"}},
          "best_oa_location": {
            "pdf_url": "https://www.researchsquare.com/article/rs-27656/v1.pdf",
            "version": "acceptedVersion", "is_oa": true, "source": {"display_name": "Research Square"}
          },
          "open_access": {"is_oa": true, "oa_status": "green"}
        }
      ]
    }
    """.trimIndent()

/** A real [OpenAlexClient] pointed at the test's [MockWebServer] — shared by every repo-constructing test. */
fun testOpenAlexClient(server: MockWebServer): OpenAlexClient =
    OpenAlexClient(
        httpClient = OkHttpClient(),
        dispatchers =
            object : DispatcherProvider {
                override val io: CoroutineDispatcher = Dispatchers.IO
                override val default: CoroutineDispatcher = Dispatchers.Default
                override val main: CoroutineDispatcher = Dispatchers.Default
            },
        mailto = "test@example.com",
        baseUrl = server.url("/oa").toString().trimEnd('/'),
        minSpacingMs = 0,
    )
