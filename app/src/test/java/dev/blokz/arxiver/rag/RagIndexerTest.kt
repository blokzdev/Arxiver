package dev.blokz.arxiver.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.ChunkEmbeddingEntity
import dev.blokz.arxiver.core.database.entity.CollectionEntity
import dev.blokz.arxiver.core.database.entity.CollectionPaperCrossRef
import dev.blokz.arxiver.core.database.entity.NoteEntity
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.search.TextChunker
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class RagIndexerTest {
    private lateinit var db: ArxiverDatabase
    private val model = "test-model"

    // Fake embedder: a fixed-dim vector per text; counts calls so idempotence is observable.
    private var embedCalls = 0
    private val embed: suspend (List<String>) -> AppResult<List<FloatArray>> = { texts ->
        embedCalls++
        AppResult.Success(texts.map { floatArrayOf(it.length.toFloat(), 1f, 0f, 0f) })
    }

    private fun indexer(
        embedFn: suspend (List<String>) -> AppResult<List<FloatArray>> = embed,
        modelName: String = model,
    ) = RagIndexer(
        paperDao = db.paperDao(),
        libraryDao = db.libraryDao(),
        chunkDao = db.chunkEmbeddingDao(),
        chunker = TextChunker(),
        modelName = modelName,
        embed = embedFn,
        clock = { 0L },
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                // Synchronous executors so the InvalidationTracker refresh can't race db.close() and
                // leak an "Illegal connection pointer" into the next test (memory
                // robolectric-room-sync-executors).
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedPaper(id: String) {
        val paper =
            Paper(
                ref = ArxivRef(ArxivId(id)),
                latestVersion = 1,
                title = "Title $id",
                abstract = "An abstract about retrieval augmented generation.",
                publishedAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                primaryCategory = "cs.LG",
                categories = listOf("cs.LG"),
                authors = listOf("A"),
                fetchedAt = Instant.EPOCH,
            )
        db.paperDao().upsertPaperWithRelations(paper.toEntity(), paper.authors, paper.categories)
    }

    @Test
    fun `indexes abstract and notes into chunk rows`() =
        runTest {
            seedPaper("p1")
            db.libraryDao().insertNote(NoteEntity(paperId = "p1", content = "A note.", createdAt = 0, updatedAt = 0))

            indexer().indexPaper("p1")

            val rows = db.chunkEmbeddingDao().chunksForPaper("p1", 100, 0)
            assertTrue(rows.any { it.sourceKind == "abstract" })
            assertTrue(rows.any { it.sourceKind == "note" })
            assertTrue(rows.all { it.model == model })
        }

    @Test
    fun `re-indexing a paper preserves its body chunks (PFT-1 no clobber)`() =
        runTest {
            seedPaper("p1")
            // A pre-existing body chunk, as PFT.2 will write it — the source RagIndexer must never touch.
            db.chunkEmbeddingDao().insert(
                listOf(
                    ChunkEmbeddingEntity(
                        paperId = "p1",
                        chunkText = "the full paper body text",
                        vector = PaperEmbeddingEntity.floatsToBlob(floatArrayOf(1f, 0f, 0f, 0f)),
                        model = model,
                        dim = 4,
                        sourceKind = ChunkEmbeddingEntity.SOURCE_BODY,
                        ordinal = 0,
                        embeddedAt = 0L,
                    ),
                ),
            )

            indexer().indexPaper("p1")

            val rows = db.chunkEmbeddingDao().chunksForPaper("p1", 100, 0)
            assertTrue(
                rows.any { it.sourceKind == ChunkEmbeddingEntity.SOURCE_BODY },
                "body survives an abstract re-index",
            )
            assertTrue(
                rows.any { it.sourceKind == ChunkEmbeddingEntity.SOURCE_ABSTRACT },
                "abstract is (re)written",
            )
        }

    @Test
    fun `re-indexing replaces prior chunks (idempotent)`() =
        runTest {
            seedPaper("p1")
            indexer().indexPaper("p1")
            val first = db.chunkEmbeddingDao().count()

            indexer().indexPaper("p1")
            assertEquals(first, db.chunkEmbeddingDao().count())
        }

    @Test
    fun `indexCollection indexes the collection's missing papers`() =
        runTest {
            seedPaper("p1")
            seedPaper("p2")
            val collectionId = db.libraryDao().createCollection(CollectionEntity(name = "KB", createdAt = 0))
            db.libraryDao().addToCollection(CollectionPaperCrossRef(collectionId, "p1", 0))
            db.libraryDao().addToCollection(CollectionPaperCrossRef(collectionId, "p2", 0))

            indexer().indexCollection(collectionId)

            assertTrue(db.chunkEmbeddingDao().chunksForPaper("p1", 100, 0).isNotEmpty())
            assertTrue(db.chunkEmbeddingDao().chunksForPaper("p2", 100, 0).isNotEmpty())
        }

    @Test
    fun `indexPaperBody writes body chunks without touching abstract or note chunks`() =
        runTest {
            seedPaper("p1")
            indexer().indexPaper("p1") // abstract chunks
            val abstractCount =
                db.chunkEmbeddingDao().chunksForPaper("p1", 100, 0)
                    .count { it.sourceKind == ChunkEmbeddingEntity.SOURCE_ABSTRACT }
            assertTrue(abstractCount > 0)

            indexer().indexPaperBody("p1", "The full body text of the paper. It has several sentences.")

            val rows = db.chunkEmbeddingDao().chunksForPaper("p1", 100, 0)
            assertTrue(rows.any { it.sourceKind == ChunkEmbeddingEntity.SOURCE_BODY }, "body chunks written")
            assertEquals(
                abstractCount,
                rows.count { it.sourceKind == ChunkEmbeddingEntity.SOURCE_ABSTRACT },
                "abstract chunks untouched by a body index",
            )
        }

    @Test
    fun `indexPaperBody with empty text clears prior body chunks and succeeds`() =
        runTest {
            seedPaper("p1")
            indexer().indexPaperBody("p1", "A real body sentence here.")
            assertTrue(
                db.chunkEmbeddingDao().chunksForPaper("p1", 100, 0)
                    .any { it.sourceKind == ChunkEmbeddingEntity.SOURCE_BODY },
            )

            val result = indexer().indexPaperBody("p1", "   ")

            assertTrue(result is AppResult.Success)
            assertTrue(
                db.chunkEmbeddingDao().chunksForPaper("p1", 100, 0)
                    .none { it.sourceKind == ChunkEmbeddingEntity.SOURCE_BODY },
                "an empty body clears any prior body chunks",
            )
        }

    @Test
    fun `unknown paper is a no-op success`() =
        runTest {
            val result = indexer().indexPaper("missing")
            assertTrue(result is AppResult.Success)
            assertEquals(0, db.chunkEmbeddingDao().count())
        }

    @Test
    fun `indexPaperBody for an orphan body (no paper row) is a no-op success`() =
        runTest {
            // A cached reader body can outlive its paper row; body indexing must not FK-fail on it.
            val result = indexer().indexPaperBody("missing", "Some body text that would otherwise be chunked.")
            assertTrue(result is AppResult.Success)
            assertEquals(0, db.chunkEmbeddingDao().count())
        }

    @Test
    fun `embedding failure leaves no chunks and surfaces the error`() =
        runTest {
            seedPaper("p1")
            val failing: suspend (List<String>) -> AppResult<List<FloatArray>> = {
                AppResult.Failure(dev.blokz.arxiver.core.common.AppError.Unexpected())
            }
            val result = indexer(embedFn = failing).indexPaper("p1")
            assertTrue(result is AppResult.Failure)
            assertEquals(0, db.chunkEmbeddingDao().count())
        }
}
