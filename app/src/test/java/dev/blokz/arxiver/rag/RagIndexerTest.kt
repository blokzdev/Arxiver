package dev.blokz.arxiver.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.NoteEntity
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.model.ArxivId
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
        db = Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedPaper(id: String) {
        val paper =
            Paper(
                id = ArxivId(id),
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
    fun `re-indexing replaces prior chunks (idempotent)`() =
        runTest {
            seedPaper("p1")
            indexer().indexPaper("p1")
            val first = db.chunkEmbeddingDao().count()

            indexer().indexPaper("p1")
            assertEquals(first, db.chunkEmbeddingDao().count())
        }

    @Test
    fun `unknown paper is a no-op success`() =
        runTest {
            val result = indexer().indexPaper("missing")
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
