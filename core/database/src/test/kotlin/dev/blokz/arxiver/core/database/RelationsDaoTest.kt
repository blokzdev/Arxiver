package dev.blokz.arxiver.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.entity.CitationEdgeEntity
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.entity.RelatedPaperEntity
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

/** Queries feeding the dispatch payload's `relations` block (SPEC-CLAUDE-BRIDGE §4). */
@RunWith(RobolectricTestRunner::class)
class RelationsDaoTest {
    private lateinit var db: ArxiverDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertPaper(id: String) {
        val paper =
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
        db.paperDao().upsertPaperWithRelations(paper.toEntity(), paper.authors, paper.categories)
    }

    @Test
    fun `edgesAmong returns only edges with both endpoints in the selection`() =
        runTest {
            listOf("2403.00001", "2403.00002", "2403.00003").forEach { insertPaper(it) }
            db.citationDao().insertEdges(
                listOf(
                    CitationEdgeEntity(citingId = "2403.00001", citedId = "2403.00002", fetchedAt = 0),
                    CitationEdgeEntity(citingId = "2403.00001", citedId = "2403.00003", fetchedAt = 0),
                ),
            )

            val edges = db.citationDao().edgesAmong(listOf("2403.00001", "2403.00002"))

            assertEquals(listOf("2403.00001" to "2403.00002"), edges.map { it.citingId to it.citedId })
        }

    @Test
    fun `neighborsFor orders by similarity and flags library membership`() =
        runTest {
            listOf("2403.00001", "2403.00002", "2403.00003").forEach { insertPaper(it) }
            db.libraryDao().upsertEntry(LibraryEntryEntity(paperId = "2403.00002", addedAt = 0))
            db.embeddingDao().insertRelated(
                listOf(
                    RelatedPaperEntity("2403.00001", "2403.00002", similarity = 0.7, computedAt = 0),
                    RelatedPaperEntity("2403.00001", "2403.00003", similarity = 0.9, computedAt = 0),
                ),
            )

            val rows = db.embeddingDao().neighborsFor("2403.00001", limit = 2)

            assertEquals(listOf("2403.00003", "2403.00002"), rows.map { it.paper.id })
            assertEquals(listOf(false, true), rows.map { it.in_library })
            assertEquals(1, db.embeddingDao().neighborsFor("2403.00001", limit = 1).size)
        }
}
