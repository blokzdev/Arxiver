package dev.blokz.arxiver.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.ai.HtmlBodyTextExtractor
import dev.blokz.arxiver.core.ai.HtmlSource
import dev.blokz.arxiver.core.ai.HtmlStorage
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.ChunkEmbeddingEntity
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.pdf.PdfTextQualityGate
import dev.blokz.arxiver.core.search.TextChunker
import dev.blokz.arxiver.data.PdfBodyStore
import dev.blokz.arxiver.data.PdfStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The PFT.2 body-indexing coordinator: extraction → body chunks → `.bodyindex` sidecar, with a re-open
 * short-circuit and a filesystem-driven backfill that self-heals a model bump.
 */
@RunWith(RobolectricTestRunner::class)
class BodyIndexerTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var filesDir: File
    private lateinit var storage: HtmlStorage
    private lateinit var pdfBodyStore: PdfBodyStore
    private val id = ArxivId("2401.00001")
    private val model = "bge-A"
    private var embedCalls = 0

    // Controllable PDF-path seams (the concrete extractor + gate are faked; their real behaviour is CI-covered
    // in :core:pdf and device-covered in VERIFICATION — here we drive BodyIndexer's arbitration/marker logic).
    private var pdfText = "The proposed method uses gradient descent to minimize the training loss over the data."
    private var pdfGateAccepts = true

    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.Unconfined
            override val default: CoroutineDispatcher = Dispatchers.Unconfined
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
        }

    private fun ragIndexer(modelName: String) =
        RagIndexer(
            paperDao = db.paperDao(),
            libraryDao = db.libraryDao(),
            chunkDao = db.chunkEmbeddingDao(),
            chunker = TextChunker(),
            modelName = modelName,
            embed = { texts ->
                embedCalls++
                AppResult.Success(texts.map { floatArrayOf(it.length.toFloat(), 1f, 0f, 0f) })
            },
            clock = { 0L },
        )

    private fun bodyIndexer(modelName: String = model) =
        BodyIndexer(
            htmlStorage = storage,
            extractor = HtmlBodyTextExtractor,
            ragIndexer = ragIndexer(modelName),
            dispatchers = dispatchers,
            modelName = modelName,
            pdfExtract = { pdfText },
            pdfBodyStore = pdfBodyStore,
            isAcceptablePdfText = { pdfGateAccepts },
        )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        filesDir = Files.createTempDirectory("bodyidx").toFile()
        storage = HtmlStorage(filesDir, dispatchers)
        pdfBodyStore = PdfBodyStore(filesDir, dispatchers)
    }

    @After
    fun tearDown() {
        db.close()
        filesDir.deleteRecursively()
    }

    private suspend fun seedPaper(version: Int = 1) {
        val paper =
            Paper(
                ref = ArxivRef(id),
                latestVersion = version,
                title = "T",
                abstract = "A",
                publishedAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                primaryCategory = "cs.LG",
                categories = listOf("cs.LG"),
                authors = listOf("A"),
                fetchedAt = Instant.EPOCH,
            )
        db.paperDao().upsertPaperWithRelations(paper.toEntity(), paper.authors, paper.categories)
    }

    private suspend fun seedWithBody(
        html: String,
        version: Int = 1,
    ) {
        seedPaper(version)
        storage.store(id, version, HtmlSource.AR5IV, html)
    }

    /** Place a (dummy) downloaded PDF + its authoritative `.pdf.id` sidecar in the cache (`pdfExtract` is faked). */
    private suspend fun seedPdf(version: Int = 1) {
        val pdfDir = File(filesDir, "pdfs").apply { mkdirs() }
        File(pdfDir, PdfStorage.safeName(id.value, version)).writeText("%PDF-1.4 dummy body")
        pdfBodyStore.storeId(id.value, version)
    }

    private suspend fun bodyChunks() =
        db.chunkEmbeddingDao().chunksForPaper(id.value, 100, 0)
            .filter { it.sourceKind == ChunkEmbeddingEntity.SOURCE_BODY }

    @Test
    fun `indexOnOpen writes body chunks and stamps the sidecar`() =
        runTest {
            seedWithBody("<p>The transformer uses self attention across tokens.</p>")

            bodyIndexer().indexOnOpen(id, 1)

            assertTrue(bodyChunks().isNotEmpty(), "body chunks written")
            assertEquals(model, storage.readBodyIndex(id, 1), "sidecar stamped with the model")
        }

    @Test
    fun `a second open of the same version does not re-embed`() =
        runTest {
            seedWithBody("<p>Body text here for indexing purposes.</p>")
            val indexer = bodyIndexer()

            indexer.indexOnOpen(id, 1)
            val callsAfterFirst = embedCalls
            indexer.indexOnOpen(id, 1)

            assertEquals(callsAfterFirst, embedCalls, "an already-current body short-circuits on the sidecar")
        }

    @Test
    fun `backfill indexes a cached body that has no sidecar yet`() =
        runTest {
            seedWithBody("<p>Please backfill me into body chunks.</p>")

            bodyIndexer().backfill(limit = 10)

            assertTrue(bodyChunks().isNotEmpty(), "the un-indexed cached body is picked up")
            assertEquals(model, storage.readBodyIndex(id, 1))
        }

    @Test
    fun `a model bump makes the stale body a backfill candidate again (self-heal)`() =
        runTest {
            seedWithBody("<p>Originally indexed under model A.</p>")
            bodyIndexer("bge-A").indexOnOpen(id, 1)
            assertEquals("bge-A", storage.readBodyIndex(id, 1))

            // The worker's model-mismatch wipe drops the old-model chunks; the sidecar still names bge-A.
            db.chunkEmbeddingDao().deleteByModelMismatch("bge-B")
            assertTrue(bodyChunks().isEmpty(), "old-model body chunks wiped")

            bodyIndexer("bge-B").backfill(limit = 10)

            assertEquals("bge-B", storage.readBodyIndex(id, 1), "re-indexed + re-stamped under the new model")
            assertTrue(bodyChunks().all { it.model == "bge-B" }, "body chunks now under the new model")
        }

    // --- P-Reader2 PFT.5.5: PDF body path ---

    @Test
    fun `indexPdf on an accepted PDF writes body chunks and stamps OK`() =
        runTest {
            seedPaper()
            seedPdf()

            bodyIndexer().indexPdf(id.value, 1)

            assertTrue(bodyChunks().isNotEmpty(), "accepted PDF body indexed")
            assertEquals("OK:$model", pdfBodyStore.readBodyIndex(id.value, 1))
        }

    @Test
    fun `indexPdf on a gate-rejected PDF writes no chunks and stamps SKIP`() =
        runTest {
            seedPaper()
            seedPdf()
            pdfGateAccepts = false

            bodyIndexer().indexPdf(id.value, 1)

            assertTrue(bodyChunks().isEmpty(), "garbage never embeds / never counts toward coverage")
            assertEquals("SKIP:${PdfTextQualityGate.GATE_VERSION}", pdfBodyStore.readBodyIndex(id.value, 1))
        }

    @Test
    fun `a second indexPdf of a current PDF does not re-embed`() =
        runTest {
            seedPaper()
            seedPdf()
            val indexer = bodyIndexer()

            indexer.indexPdf(id.value, 1)
            val callsAfterFirst = embedCalls
            indexer.indexPdf(id.value, 1)

            assertEquals(callsAfterFirst, embedCalls, "an OK-marked PDF short-circuits")
        }

    @Test
    fun `indexPdf DEFERS when the HTML body is already indexed (no clobber)`() =
        runTest {
            // The clobber-race regression guard: HTML (preferred) is indexed first; the PDF path must not
            // overwrite the cleaner HTML body chunks, and must leave itself unmarked (so it can index if HTML
            // is later wiped by a model bump).
            seedWithBody("<p>The transformer uses self attention across many tokens in a sequence.</p>")
            val indexer = bodyIndexer()
            indexer.indexOnOpen(id, 1)
            val htmlBody = bodyChunks().joinToString(" ") { it.chunkText }
            assertTrue("attention" in htmlBody, "HTML body indexed first")

            pdfText = "A completely different pdf-derived body about quantum widgets and sprockets."
            seedPdf()
            indexer.indexPdf(id.value, 1)

            assertEquals(htmlBody, bodyChunks().joinToString(" ") { it.chunkText }, "HTML chunks untouched")
            assertTrue("quantum" !in bodyChunks().joinToString(" ") { it.chunkText }, "no PDF text leaked in")
            assertEquals(null, pdfBodyStore.readBodyIndex(id.value, 1), "PDF left unmarked (HTML deferred, not gated)")
        }

    @Test
    fun `a SKIP is gate-keyed so a model bump does not re-attempt garbage`() =
        runTest {
            seedPaper()
            seedPdf()
            pdfGateAccepts = false
            bodyIndexer("bge-A").indexPdf(id.value, 1)
            assertEquals("SKIP:${PdfTextQualityGate.GATE_VERSION}", pdfBodyStore.readBodyIndex(id.value, 1))

            pdfGateAccepts = true // even if it *would* now pass, the SKIP marker short-circuits under a new model
            val before = embedCalls
            bodyIndexer("bge-B").indexPdf(id.value, 1)

            assertEquals(before, embedCalls, "SKIP is keyed on GATE_VERSION, not the model — no re-attempt")
        }

    @Test
    fun `backfillPdf indexes a cached PDF that has no marker yet`() =
        runTest {
            seedPaper()
            seedPdf()

            bodyIndexer().backfillPdf(limit = 10)

            assertTrue(bodyChunks().isNotEmpty(), "the un-marked cached PDF is picked up")
            assertEquals("OK:$model", pdfBodyStore.readBodyIndex(id.value, 1))
        }
}
