package dev.blokz.arxiver.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.entity.NoteEntity
import dev.blokz.arxiver.core.database.fts.LocalKeywordSearch
import dev.blokz.arxiver.core.database.fts.buildMatchQuery
import dev.blokz.arxiver.core.model.ArxivId
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
class LocalKeywordSearchTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var search: LocalKeywordSearch

    private fun paper(
        id: String,
        title: String,
        abstract: String,
        authors: List<String>,
    ) = Paper(
        id = ArxivId(id),
        latestVersion = 1,
        title = title,
        abstract = abstract,
        publishedAt = Instant.parse("2024-03-02T10:00:00Z"),
        updatedAt = Instant.parse("2024-03-02T10:00:00Z"),
        primaryCategory = "cs.LG",
        categories = listOf("cs.LG"),
        authors = authors,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java).build()
        search = LocalKeywordSearch(db.searchDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed() {
        val dao = db.paperDao()
        paper(
            id = "2401.00001",
            title = "Quantum Entanglement Protocols",
            abstract = "We discuss teleportation across noisy channels.",
            authors = listOf("Alice Quantum"),
        ).let { dao.upsertPaperWithRelations(it.toEntity(), it.authors, it.categories) }
        paper(
            id = "2401.00002",
            title = "Sparse Attention Mechanisms",
            abstract = "Quantum-inspired sparsity for transformers at scale.",
            authors = listOf("Bob Sparse"),
        ).let { dao.upsertPaperWithRelations(it.toEntity(), it.authors, it.categories) }
    }

    @Test
    fun `finds papers by title and abstract, title outranks abstract`() =
        runTest {
            seed()
            val hits = search.search("quantum")
            assertEquals(2, hits.size)
            assertEquals("2401.00001", hits.first().paper.id)
            assertTrue(hits[0].score > hits[1].score)
        }

    @Test
    fun `finds papers by author name`() =
        runTest {
            seed()
            assertEquals(listOf("2401.00002"), search.search("bob").map { it.paper.id })
        }

    @Test
    fun `prefix matching on the final term`() =
        runTest {
            seed()
            assertEquals(listOf("2401.00002"), search.search("atten").map { it.paper.id })
        }

    @Test
    fun `note content matches and boosts its paper`() =
        runTest {
            seed()
            db.libraryDao().insertNote(
                NoteEntity(
                    paperId = "2401.00001",
                    content = "compare with surface codes",
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
            assertEquals(listOf("2401.00001"), search.search("surface codes").map { it.paper.id })
        }

    @Test
    fun `note update and delete reindex`() =
        runTest {
            seed()
            val dao = db.libraryDao()
            val noteId =
                dao.insertNote(
                    NoteEntity(paperId = "2401.00001", content = "ephemeral marker", createdAt = 1, updatedAt = 1),
                )
            assertEquals(1, search.search("ephemeral").size)
            dao.updateNote(noteId, "replacement text", updatedAt = 2)
            assertTrue(search.search("ephemeral").isEmpty())
            assertEquals(1, search.search("replacement").size)
            dao.deleteNote(noteId)
            assertTrue(search.search("replacement").isEmpty())
        }

    @Test
    fun `paper re-upsert reindexes without duplicates`() =
        runTest {
            seed()
            val updated =
                paper(
                    id = "2401.00001",
                    title = "Topological Quantum Memory",
                    abstract = "New title same paper.",
                    authors = listOf("Alice Quantum"),
                )
            db.paperDao().upsertPaperWithRelations(updated.toEntity(), updated.authors, updated.categories)
            assertEquals(listOf("2401.00001"), search.search("topological").map { it.paper.id })
            assertEquals(1, search.search("quantum").count { it.paper.id == "2401.00001" })
        }

    @Test
    fun `blank query returns nothing`() =
        runTest {
            seed()
            assertTrue(search.search("   ").isEmpty())
        }

    @Test
    fun `match query builder escapes and prefixes`() {
        assertEquals("\"state\" \"space*\"", buildMatchQuery("state space"))
        assertEquals("\"a*\"", buildMatchQuery("  a  "))
        assertEquals("", buildMatchQuery("   "))
        assertEquals("\"attention*\"", buildMatchQuery("\"attention\""))
    }

    @Test
    fun `library entry round trip`() =
        runTest {
            seed()
            val dao = db.libraryDao()
            dao.upsertEntry(LibraryEntryEntity(paperId = "2401.00001", addedAt = 100))
            assertEquals(1, dao.count())
            dao.setStatus("2401.00001", LibraryEntryEntity.STATUS_READ)
            dao.removeEntry("2401.00001")
            assertEquals(0, dao.count())
        }
}
