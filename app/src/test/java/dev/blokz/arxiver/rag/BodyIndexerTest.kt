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
import dev.blokz.arxiver.core.search.TextChunker
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
    private val id = ArxivId("2401.00001")
    private val model = "bge-A"
    private var embedCalls = 0

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
        BodyIndexer(storage, HtmlBodyTextExtractor, ragIndexer(modelName), dispatchers, modelName)

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
    }

    @After
    fun tearDown() {
        db.close()
        filesDir.deleteRecursively()
    }

    private suspend fun seedWithBody(
        html: String,
        version: Int = 1,
    ) {
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
        storage.store(id, version, HtmlSource.AR5IV, html)
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
}
