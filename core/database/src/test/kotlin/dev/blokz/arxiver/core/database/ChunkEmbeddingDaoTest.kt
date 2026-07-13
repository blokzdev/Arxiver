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
import kotlinx.coroutines.flow.first
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

    // --- P-FullText PFT.1: source-scoped chunk maintenance ---

    @Test
    fun `deleteForPaperSources deletes only the named source kinds`() =
        runTest {
            seedPaper("p1")
            val dao = db.chunkEmbeddingDao()
            dao.insert(
                listOf(
                    chunk("p1", "abstract chunk", 0, ChunkEmbeddingEntity.SOURCE_ABSTRACT),
                    chunk("p1", "note chunk", 0, ChunkEmbeddingEntity.SOURCE_NOTE),
                    chunk("p1", "body chunk", 0, ChunkEmbeddingEntity.SOURCE_BODY),
                ),
            )

            dao.deleteForPaperSources(
                "p1",
                listOf(ChunkEmbeddingEntity.SOURCE_ABSTRACT, ChunkEmbeddingEntity.SOURCE_NOTE),
            )

            assertEquals(
                listOf(ChunkEmbeddingEntity.SOURCE_BODY),
                dao.chunksForPaper("p1", 100, 0).map { it.sourceKind },
                "only abstract+note are deleted; the body chunk survives",
            )
        }

    @Test
    fun `replacePaperSources swaps only the named sources and preserves the others`() =
        runTest {
            seedPaper("p1")
            val dao = db.chunkEmbeddingDao()
            dao.insert(
                listOf(
                    chunk("p1", "old abstract", 0, ChunkEmbeddingEntity.SOURCE_ABSTRACT),
                    chunk("p1", "body stays", 0, ChunkEmbeddingEntity.SOURCE_BODY),
                ),
            )

            dao.replacePaperSources(
                "p1",
                listOf(ChunkEmbeddingEntity.SOURCE_ABSTRACT, ChunkEmbeddingEntity.SOURCE_NOTE),
                listOf(chunk("p1", "new abstract", 0, ChunkEmbeddingEntity.SOURCE_ABSTRACT)),
            )

            assertEquals(
                setOf("new abstract", "body stays"),
                dao.chunksForPaper("p1", 100, 0).map { it.chunkText }.toSet(),
                "the abstract is swapped in one txn; the body chunk is untouched",
            )
        }

    @Test
    fun `libraryPapersMissingChunks selects a body-only paper (abstract-scoped, no starvation)`() =
        runTest {
            seedPaper("p1")
            db.libraryDao().upsertEntry(LibraryEntryEntity(paperId = "p1", addedAt = 1))
            // p1 has a current-model BODY chunk but no abstract chunk (the PFT.2 body-first ordering).
            db.chunkEmbeddingDao().insert(listOf(chunk("p1", "body only", 0, ChunkEmbeddingEntity.SOURCE_BODY)))

            assertEquals(
                listOf("p1"),
                db.chunkEmbeddingDao().libraryPapersMissingChunks(model, limit = 10),
                "a body-only paper still needs abstract indexing — must not be starved",
            )
        }

    @Test
    fun `collectionPapersMissingChunks selects a body-only paper (abstract-scoped, no starvation)`() =
        runTest {
            seedPaper("p1")
            val dao = db.chunkEmbeddingDao()
            val collectionId = db.libraryDao().createCollection(CollectionEntity(name = "KB", createdAt = 0))
            db.libraryDao().addToCollection(CollectionPaperCrossRef(collectionId, "p1", 0))
            dao.insert(listOf(chunk("p1", "body only", 0, ChunkEmbeddingEntity.SOURCE_BODY)))

            assertEquals(
                listOf("p1"),
                dao.collectionPapersMissingChunks(collectionId, model, limit = 10),
                "a body-only collection paper still needs abstract indexing",
            )
        }

    // --- P-FullText PFT.3: the corpus-wide body FTS leg + coverage count -----------------------------

    @Test
    fun `matchBodyChunks matches only body chunks corpus-wide`() =
        runTest {
            seedPaper("p1")
            seedPaper("p2")
            val dao = db.chunkEmbeddingDao()
            dao.insert(
                listOf(
                    chunk("p1", "transformers use attention mechanisms", 0, ChunkEmbeddingEntity.SOURCE_BODY),
                    // Same term in an ABSTRACT chunk — must NOT be a full-text (body) hit.
                    chunk("p1", "attention in the abstract", 0, ChunkEmbeddingEntity.SOURCE_ABSTRACT),
                    chunk("p2", "attention across tokens", 0, ChunkEmbeddingEntity.SOURCE_BODY),
                ),
            )

            val hits = dao.matchBodyChunks("attention")

            assertEquals(
                setOf("p1", "p2"),
                hits.map { it.paperId }.toSet(),
                "both papers match via their BODY chunks; the abstract chunk is not a body hit",
            )
        }

    @Test
    fun `observeBodyIndexedPaperCount counts distinct body-indexed papers for the model`() =
        runTest {
            seedPaper("p1")
            seedPaper("p2")
            val dao = db.chunkEmbeddingDao()
            dao.insert(
                listOf(
                    chunk("p1", "body a", 0, ChunkEmbeddingEntity.SOURCE_BODY),
                    // A second body chunk for the same paper → still counts once (DISTINCT paper_id).
                    chunk("p1", "body b", 1, ChunkEmbeddingEntity.SOURCE_BODY),
                    // An abstract chunk is not a body chunk → not counted.
                    chunk("p2", "abstract only", 0, ChunkEmbeddingEntity.SOURCE_ABSTRACT),
                ),
            )

            assertEquals(1, dao.observeBodyIndexedPaperCount(model).first())
        }
}
