package dev.blokz.arxiver.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.entity.CollectionEntity
import dev.blokz.arxiver.core.database.entity.CollectionPaperCrossRef
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.entity.NoteEntity
import dev.blokz.arxiver.core.database.entity.PaperTagCrossRef
import dev.blokz.arxiver.core.database.entity.TagEntity
import dev.blokz.arxiver.core.model.ArxivId
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the library DAO surface (entries, collections, tags, notes) the audit found untested. */
@RunWith(RobolectricTestRunner::class)
class LibraryDaoTest {
    private lateinit var db: ArxiverDatabase
    private val dao get() = db.libraryDao()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertPaper(id: String) {
        val p =
            Paper(
                id = ArxivId(id),
                latestVersion = 1,
                title = "Paper $id",
                abstract = "Abstract $id",
                publishedAt = Instant.parse("2024-03-02T10:00:00Z"),
                updatedAt = Instant.parse("2024-03-15T10:00:00Z"),
                primaryCategory = "cs.LG",
                categories = listOf("cs.LG"),
                authors = listOf("A. Researcher"),
                fetchedAt = Instant.parse("2026-06-11T00:00:00Z"),
            )
        db.paperDao().upsertPaperWithRelations(p.toEntity(), p.authors, p.categories)
    }

    @Test
    fun `entry status and rating update in place`() =
        runTest {
            insertPaper("2403.00001")
            dao.upsertEntry(LibraryEntryEntity(paperId = "2403.00001", addedAt = 0))
            assertEquals(1, dao.count())

            dao.setStatus("2403.00001", LibraryEntryEntity.STATUS_READING)
            dao.setRating("2403.00001", 5)
            val entry = dao.observeEntry("2403.00001").first()
            assertEquals(LibraryEntryEntity.STATUS_READING, entry?.status)
            assertEquals(5, entry?.rating)

            dao.removeEntry("2403.00001")
            assertEquals(0, dao.count())
            assertNull(dao.observeEntry("2403.00001").first())
        }

    @Test
    fun `collection membership is reflected in papers and size`() =
        runTest {
            insertPaper("2403.00001")
            insertPaper("2403.00002")
            val cid = dao.createCollection(CollectionEntity(name = "Reading list", createdAt = 0))

            dao.addToCollection(CollectionPaperCrossRef(cid, "2403.00001", addedAt = 1))
            dao.addToCollection(CollectionPaperCrossRef(cid, "2403.00002", addedAt = 2))
            assertEquals(2, dao.observeCollectionSize(cid).first())
            // Ordered by added_at DESC.
            assertEquals(
                listOf("2403.00002", "2403.00001"),
                dao.observeCollectionPapers(cid).first().map { it.paper.id },
            )

            dao.removeFromCollection(cid, "2403.00002")
            assertEquals(1, dao.observeCollectionSize(cid).first())
        }

    @Test
    fun `deleting a collection cascades its membership rows`() =
        runTest {
            insertPaper("2403.00001")
            val cid = dao.createCollection(CollectionEntity(name = "Temp", createdAt = 0))
            dao.addToCollection(CollectionPaperCrossRef(cid, "2403.00001", addedAt = 0))

            dao.deleteCollection(cid)
            assertEquals(0, dao.observeCollectionSize(cid).first())
            assertTrue(dao.observeCollections().first().isEmpty())
        }

    @Test
    fun `tag names are case-insensitively unique and link to papers`() =
        runTest {
            insertPaper("2403.00001")
            val tagId = dao.insertTag(TagEntity(name = "ml"))
            // Same name in different case is ignored; lookup is NOCASE.
            dao.insertTag(TagEntity(name = "ML"))
            assertEquals(1, dao.observeTags().first().size)
            assertEquals(tagId, dao.tagIdByName("ML"))

            dao.addPaperTag(PaperTagCrossRef(paperId = "2403.00001", tagId = tagId))
            assertEquals(listOf("2403.00001"), dao.observeTagPapers(tagId).first().map { it.paper.id })
            assertEquals(listOf("ml"), dao.observeTagsFor("2403.00001").first().map { it.name })

            dao.removePaperTag("2403.00001", tagId)
            assertTrue(dao.observeTagPapers(tagId).first().isEmpty())
        }

    @Test
    fun `notes insert update and delete per paper`() =
        runTest {
            insertPaper("2403.00001")
            val id = dao.insertNote(NoteEntity(paperId = "2403.00001", content = "first", createdAt = 0, updatedAt = 0))

            dao.updateNote(id, content = "edited", updatedAt = 5)
            assertEquals("edited", dao.notesFor("2403.00001").single().content)

            dao.deleteNote(id)
            assertTrue(dao.observeNotesFor("2403.00001").first().isEmpty())
        }

    @Test
    fun `collection memberships reflect add and remove`() =
        runTest {
            insertPaper("2403.00001")
            val a = dao.createCollection(CollectionEntity(name = "A", createdAt = 0))
            val b = dao.createCollection(CollectionEntity(name = "B", createdAt = 0))

            dao.addToCollection(CollectionPaperCrossRef(a, "2403.00001", addedAt = 0))
            dao.addToCollection(CollectionPaperCrossRef(b, "2403.00001", addedAt = 0))
            assertEquals(setOf(a, b), dao.observeCollectionMemberships("2403.00001").first().toSet())

            dao.removeFromCollection(a, "2403.00001")
            assertEquals(listOf(b), dao.observeCollectionMemberships("2403.00001").first())
        }
}
