package dev.blokz.arxiver.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.entity.ChunkEmbeddingEntity
import dev.blokz.arxiver.core.database.entity.CollectionEntity
import dev.blokz.arxiver.core.database.entity.CollectionPaperCrossRef
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.Paper
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
class ChunkEmbeddingDaoTest {
    private lateinit var db: ArxiverDatabase
    private val model = "bge-small-en-v1.5-q8"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedPaper(
        id: String,
        title: String = "Title",
        abstract: String = "Abstract",
    ) {
        val paper =
            Paper(
                ref = ArxivRef(ArxivId(id)),
                latestVersion = 1,
                title = title,
                abstract = abstract,
                publishedAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                primaryCategory = "cs.LG",
                categories = listOf("cs.LG"),
                authors = listOf("A"),
                fetchedAt = Instant.EPOCH,
            )
        db.paperDao().upsertPaperWithRelations(paper.toEntity(), paper.authors, paper.categories)
    }

    private fun chunk(
        paperId: String,
        text: String,
        ordinal: Int,
        sourceKind: String = ChunkEmbeddingEntity.SOURCE_ABSTRACT,
        model: String = this.model,
        vector: FloatArray = FloatArray(4) { it.toFloat() },
    ) = ChunkEmbeddingEntity(
        paperId = paperId,
        chunkText = text,
        vector = PaperEmbeddingEntity.floatsToBlob(vector),
        model = model,
        dim = vector.size,
        sourceKind = sourceKind,
        ordinal = ordinal,
        embeddedAt = 0L,
    )

    @Test
    fun `insert and read scoped by paper`() =
        runTest {
            seedPaper("p1")
            val dao = db.chunkEmbeddingDao()
            dao.insert(
                listOf(
                    chunk("p1", "transformers attend to tokens", 0),
                    chunk("p1", "a personal note about graphs", 0, ChunkEmbeddingEntity.SOURCE_NOTE),
                ),
            )

            val rows = dao.chunksForPaper("p1", limit = 100, offset = 0)
            assertEquals(2, rows.size)
            assertEquals(2, dao.count())
        }

    @Test
    fun `fts match is scoped to the paper`() =
        runTest {
            seedPaper("p1")
            seedPaper("p2")
            val dao = db.chunkEmbeddingDao()
            dao.insert(listOf(chunk("p1", "graph neural networks", 0)))
            dao.insert(listOf(chunk("p2", "graph neural networks", 0)))

            val p1Hits = dao.matchChunksForPaper("\"graph*\"", "p1", limit = 50)
            assertEquals(1, p1Hits.size)
            // the matched chunk belongs to p1
            val p1ChunkIds = dao.chunksForPaper("p1", 100, 0).map { it.id }.toSet()
            assertTrue(p1Hits.single().chunkId in p1ChunkIds)
        }

    @Test
    fun `collection scope joins collection_papers`() =
        runTest {
            seedPaper("p1")
            seedPaper("p2")
            val dao = db.chunkEmbeddingDao()
            val collectionId = db.libraryDao().createCollection(CollectionEntity(name = "KB", createdAt = 0))
            db.libraryDao().addToCollection(CollectionPaperCrossRef(collectionId, "p1", 0))
            dao.insert(listOf(chunk("p1", "in the kb", 0)))
            dao.insert(listOf(chunk("p2", "not in the kb", 0)))

            val rows = dao.chunksForCollection(collectionId, limit = 100, offset = 0)
            assertEquals(listOf("p1"), rows.map { it.paperId })
        }

    @Test
    fun `model mismatch wipe drops other-model chunks only`() =
        runTest {
            seedPaper("p1")
            val dao = db.chunkEmbeddingDao()
            dao.insert(listOf(chunk("p1", "current", 0, model = model)))
            dao.insert(listOf(chunk("p1", "stale", 1, model = "old-model")))

            dao.deleteByModelMismatch(model)
            assertEquals(1, dao.count())
            assertEquals("current", dao.chunksForPaper("p1", 100, 0).single().chunkText)
        }

    @Test
    fun `deleting a paper cascades to its chunks`() =
        runTest {
            seedPaper("p1")
            val dao = db.chunkEmbeddingDao()
            dao.insert(listOf(chunk("p1", "doomed", 0)))
            assertEquals(1, dao.count())

            db.openHelper.writableDatabase.execSQL("DELETE FROM papers WHERE id = 'p1'")
            assertEquals(0, dao.count())
        }

    @Test
    fun `collectionPapersMissingChunks returns only un-indexed collection papers`() =
        runTest {
            seedPaper("p1")
            seedPaper("p2")
            val dao = db.chunkEmbeddingDao()
            val collectionId = db.libraryDao().createCollection(CollectionEntity(name = "KB", createdAt = 0))
            db.libraryDao().addToCollection(CollectionPaperCrossRef(collectionId, "p1", 0))
            db.libraryDao().addToCollection(CollectionPaperCrossRef(collectionId, "p2", 0))
            dao.insert(listOf(chunk("p1", "already indexed", 0)))

            val missing = dao.collectionPapersMissingChunks(collectionId, model, limit = 10)
            assertEquals(listOf("p2"), missing)
        }

    @Test
    fun `libraryPapersMissingChunks returns only un-indexed library papers`() =
        runTest {
            seedPaper("p1")
            seedPaper("p2")
            db.libraryDao().upsertEntry(LibraryEntryEntity(paperId = "p1", addedAt = 1))
            db.libraryDao().upsertEntry(LibraryEntryEntity(paperId = "p2", addedAt = 2))
            db.chunkEmbeddingDao().insert(listOf(chunk("p1", "already indexed", 0)))

            val missing = db.chunkEmbeddingDao().libraryPapersMissingChunks(model, limit = 10)
            assertEquals(listOf("p2"), missing)
        }
}
