package dev.blokz.arxiver.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.entity.ReadingPositionEntity
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

@RunWith(RobolectricTestRunner::class)
class ReadingPositionDaoTest {
    private lateinit var db: ArxiverDatabase
    private val floor = 0.02f

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
                ref = ArxivRef(ArxivId(id)),
                latestVersion = 1,
                title = "Title $id",
                abstract = "Abstract",
                publishedAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                primaryCategory = "cs.LG",
                categories = listOf("cs.LG"),
                authors = listOf("A"),
                fetchedAt = Instant.EPOCH,
            )
        db.paperDao().upsertPaperWithRelations(paper.toEntity(), paper.authors, paper.categories)
    }

    private suspend fun position(
        paperId: String,
        surface: String = ReadingPositionEntity.SURFACE_HTML,
        fraction: Float = 0.5f,
        finished: Boolean = false,
        updatedAt: Long = 1_000L,
    ) = db.readingPositionDao().upsert(
        ReadingPositionEntity(
            paperId = paperId,
            surface = surface,
            version = 1,
            anchorId = if (surface == ReadingPositionEntity.SURFACE_HTML) "S1" else null,
            offsetPx = 10,
            fraction = fraction,
            pageIndex = if (surface == ReadingPositionEntity.SURFACE_PDF) 3 else null,
            finished = finished,
            updatedAt = updatedAt,
        ),
    )

    private suspend fun shelf(recencyFloor: Long = 0L) =
        db.readingPositionDao().observeContinueReading(floor, recencyFloor, limit = 10).first()

    @Test
    fun `a scrolled unfinished recent paper appears`() =
        runTest {
            seedPaper("p1")
            position("p1", fraction = 0.5f)
            assertEquals(listOf("p1"), shelf().map { it.paper.id })
        }

    @Test
    fun `a row below the progress floor is excluded (opened != reading)`() =
        runTest {
            seedPaper("p1")
            position("p1", fraction = 0.01f)
            assertEquals(emptyList(), shelf().map { it.paper.id })
        }

    @Test
    fun `a finished paper is excluded paper-wide, not resurfaced by a later glance on another surface`() =
        runTest {
            seedPaper("p1")
            position(
                "p1",
                surface = ReadingPositionEntity.SURFACE_HTML,
                fraction = 0.96f,
                finished = true,
                updatedAt = 100,
            )
            // A later 3% glance in the PDF viewer must NOT resurface a paper finished in HTML.
            position("p1", surface = ReadingPositionEntity.SURFACE_PDF, fraction = 0.03f, updatedAt = 500)
            assertEquals(emptyList(), shelf().map { it.paper.id }, "any finished surface excludes the paper")
        }

    @Test
    fun `a library-read paper is excluded`() =
        runTest {
            seedPaper("p1")
            position("p1", fraction = 0.5f)
            db.libraryDao().upsertEntry(
                LibraryEntryEntity(paperId = "p1", addedAt = 1, status = LibraryEntryEntity.STATUS_READ),
            )
            assertEquals(emptyList(), shelf().map { it.paper.id })
        }

    @Test
    fun `a row outside the recency window is excluded`() =
        runTest {
            seedPaper("p1")
            position("p1", fraction = 0.5f, updatedAt = 1_000L)
            assertEquals(emptyList(), shelf(recencyFloor = 5_000L).map { it.paper.id })
        }

    @Test
    fun `an orphan row with no paper self-filters (INNER JOIN, no FK)`() =
        runTest {
            position("ghost", fraction = 0.5f) // no seedPaper
            assertEquals(emptyList(), shelf().map { it.paper.id })
        }

    @Test
    fun `cross-surface represents a paper by its furthest-progress row, not the most recent glance`() =
        runTest {
            seedPaper("p1")
            position("p1", surface = ReadingPositionEntity.SURFACE_HTML, fraction = 0.8f, updatedAt = 100)
            // A later, shallower PDF glance must NOT bury the real 80% HTML progress.
            position("p1", surface = ReadingPositionEntity.SURFACE_PDF, fraction = 0.05f, updatedAt = 900)

            val rows = shelf()
            assertEquals(1, rows.size, "one card per paper")
            assertEquals(ReadingPositionEntity.SURFACE_HTML, rows.single().surface, "furthest progress wins")
            assertEquals(0.8f, rows.single().fraction, 1e-6f)
        }
}
