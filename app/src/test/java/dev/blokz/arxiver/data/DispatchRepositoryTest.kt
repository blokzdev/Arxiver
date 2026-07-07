package dev.blokz.arxiver.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.claude.PayloadBuilder
import dev.blokz.arxiver.core.claude.PayloadResult
import dev.blokz.arxiver.core.claude.RoutineAction
import dev.blokz.arxiver.core.claude.RoutineTriggerClient
import dev.blokz.arxiver.core.claude.TokenVault
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.RelatedPaperEntity
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.ExternalRef
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.PaperSource
import dev.blokz.arxiver.core.model.Source
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P-Dispatch PD.2: the dispatch payload is source-aware — a non-arXiv paper is no longer filtered out.
 * Tested through the public `previewPayload` (builds the exact JSON the confirm sheet shows; the token
 * vault + trigger client are constructed but never invoked on this path).
 */
@RunWith(RobolectricTestRunner::class)
class DispatchRepositoryTest {
    private val fixedNow = Instant.parse("2026-06-11T18:30:00Z")
    private lateinit var db: ArxiverDatabase
    private lateinit var repo: DispatchRepository

    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.Default
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Default
        }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        repo =
            DispatchRepository(
                routineDao = db.routineDao(),
                tokenVault = TokenVault(context),
                payloadBuilder = PayloadBuilder(appVersion = "1.0.0", now = { fixedNow }),
                triggerClient = RoutineTriggerClient(OkHttpClient(), dispatchers),
                libraryDao = db.libraryDao(),
                paperDao = db.paperDao(),
                citationDao = db.citationDao(),
                embeddingDao = db.embeddingDao(),
            )
    }

    @After
    fun tearDown() = db.close()

    private fun arxivPaper(id: String) =
        Paper(
            ref = ArxivRef(ArxivId(id)),
            latestVersion = 1,
            title = "arXiv $id",
            abstract = "a",
            publishedAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            primaryCategory = "cs.LG",
            categories = listOf("cs.LG"),
            authors = listOf("A"),
            pdfUrl = "https://arxiv.org/pdf/$id",
        )

    private fun chemPaper(
        nativeId: String,
        doi: String?,
        pdfUrl: String,
    ) = Paper(
        ref = ExternalRef(Source.CHEMRXIV, nativeId),
        latestVersion = 1,
        title = "A Chemistry Preprint",
        abstract = "a",
        publishedAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        primaryCategory = "",
        categories = emptyList(),
        authors = listOf("Curie"),
        doi = doi,
        pdfUrl = pdfUrl,
    )

    private suspend fun seed(p: Paper) = db.paperDao().upsertPaperWithRelations(p.toEntity(), p.authors, p.categories)

    private suspend fun preview(
        paperIds: List<String>,
        includeNotes: Boolean = false,
    ): PayloadResult = repo.previewPayload(RoutineAction.DIGEST, "x", paperIds, includeNotes)

    private fun PayloadResult.papers() =
        Json.parseToJsonElement((this as PayloadResult.Ready).json).jsonObject.getValue("papers").jsonArray

    private fun JsonObject.jsonStr(key: String): String = getValue(key).jsonPrimitive.content

    @Test
    fun `a chemRxiv paper now builds a source-aware payload (un-gated)`() =
        runTest {
            seed(chemPaper("10.26434/xyz", doi = "10.26434/xyz", pdfUrl = "https://chemrxiv.org/xyz.pdf"))

            val ready = assertIs<PayloadResult.Ready>(preview(listOf("chemrxiv:10.26434/xyz")))

            assertEquals(1, ready.paperCount, "un-gated: a chemRxiv paper is no longer filtered out (was 0)")
            val p = ready.papers()[0].jsonObject
            assertTrue(p["arxiv_id"] == null, "no arxiv_id on a chemRxiv paper")
            assertEquals("chemrxiv", p.jsonStr("source"))
            assertEquals("10.26434/xyz", p.jsonStr("native_id"))
            assertEquals("https://doi.org/10.26434/xyz", p.jsonStr("url"))
            assertEquals("false", p.jsonStr("pdf_fetchable"))
        }

    @Test
    fun `a mixed selection carries both an arXiv and a non-arXiv paper`() =
        runTest {
            seed(arxivPaper("2403.01234"))
            seed(chemPaper("10.26434/xyz", doi = "10.26434/xyz", pdfUrl = "https://chemrxiv.org/xyz.pdf"))

            val ready = assertIs<PayloadResult.Ready>(preview(listOf("2403.01234", "chemrxiv:10.26434/xyz")))

            assertEquals(2, ready.paperCount)
        }

    @Test
    fun `a non-arXiv paper with no citeable link is dropped`() =
        runTest {
            seed(chemPaper("10.26434/nolink", doi = null, pdfUrl = ""))

            val ready = assertIs<PayloadResult.Ready>(preview(listOf("chemrxiv:10.26434/nolink")))

            assertEquals(0, ready.paperCount, "no DOI + blank pdfUrl → dropped (feeds NothingToDispatch)")
        }

    @Test
    fun `a missing id is skipped while present papers still build (partial-miss)`() =
        runTest {
            seed(arxivPaper("2403.01234"))

            val ready = assertIs<PayloadResult.Ready>(preview(listOf("2403.01234", "2404.99999")))

            assertEquals(1, ready.paperCount)
        }

    @Test
    fun `neighbors are source-aware and exclude skeletal citation stubs`() =
        runTest {
            seed(arxivPaper("2403.01234")) // the selected paper
            seed(chemPaper("10.26434/nbr", doi = "10.26434/nbr", pdfUrl = "https://chemrxiv.org/nbr.pdf"))
            seed(arxivPaper("2999.00001").copy(source = PaperSource.S2_STUB)) // a skeletal citation stub
            db.embeddingDao().insertRelated(
                listOf(
                    RelatedPaperEntity("2403.01234", "chemrxiv:10.26434/nbr", similarity = 0.8, computedAt = 0),
                    RelatedPaperEntity("2403.01234", "2999.00001", similarity = 0.7, computedAt = 0),
                ),
            )

            val ready = assertIs<PayloadResult.Ready>(preview(listOf("2403.01234"), includeNotes = true))
            val rel = Json.parseToJsonElement(ready.json).jsonObject.getValue("relations").jsonObject
            val neighbors = rel.getValue("library_neighbors").jsonArray

            assertEquals(1, neighbors.size, "the S2_STUB neighbor is excluded")
            val n = neighbors[0].jsonObject
            assertTrue(n["arxiv_id"] == null)
            assertTrue(n["abs_url"] == null, "a non-arXiv neighbor never carries a mangled arxiv abs_url")
            assertEquals("chemrxiv", n.jsonStr("source"))
            assertEquals("https://doi.org/10.26434/nbr", n.jsonStr("url"))
        }
}
