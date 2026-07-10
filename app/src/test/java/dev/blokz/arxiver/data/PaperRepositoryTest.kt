package dev.blokz.arxiver.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
        repo = PaperRepository(client, db.paperDao())
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
}
