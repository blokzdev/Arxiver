package dev.blokz.arxiver.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.InboxItemEntity
import dev.blokz.arxiver.core.database.toEntity
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The triage-undo round trip (Today swipe + snackbar undo): undoing a save
 * removes the library entry AND restores the inbox row; undoing a dismiss
 * restores it; both bring the paper back into the active inbox.
 */
@RunWith(RobolectricTestRunner::class)
class InboxTriageUndoTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var inboxRepository: InboxRepository

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
        libraryRepository = LibraryRepository(db.libraryDao(), db.inboxDao())
        inboxRepository = InboxRepository(db.inboxDao(), libraryRepository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedInboxPaper(id: String) {
        val paper =
            Paper(
                ref = ArxivRef(ArxivId(id)),
                latestVersion = 1,
                title = "T $id",
                abstract = "A",
                publishedAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                primaryCategory = "cs.LG",
                categories = listOf("cs.LG"),
                authors = listOf("A"),
            )
        db.paperDao().upsertPaperWithRelations(paper.toEntity(), paper.authors, paper.categories)
        db.inboxDao().insertAll(
            listOf(
                InboxItemEntity(paperId = id, followId = 1L, arrivedAt = 1L, state = InboxItemEntity.STATE_NEW),
            ),
        )
    }

    private suspend fun activeIds(): List<String> = inboxRepository.observeInbox().first().map { it.paper.id.value }

    @Test
    fun `undoing a save removes the library entry and restores the inbox row`() =
        runTest {
            seedInboxPaper("2401.00001")
            inboxRepository.saveToLibrary("2401.00001")
            assertTrue(libraryRepository.observeIsSaved("2401.00001").first())
            assertFalse("2401.00001" in activeIds())

            // What TodayViewModel.undo does for a SAVED event.
            libraryRepository.unsave("2401.00001")
            inboxRepository.restoreState("2401.00001", InboxItemEntity.STATE_NEW)

            assertFalse(libraryRepository.observeIsSaved("2401.00001").first())
            assertEquals(listOf("2401.00001"), activeIds())
        }

    @Test
    fun `undoing a dismiss restores the inbox row in its prior state`() =
        runTest {
            seedInboxPaper("2401.00002")
            inboxRepository.dismiss("2401.00002")
            assertFalse("2401.00002" in activeIds())

            inboxRepository.restoreState("2401.00002", InboxItemEntity.STATE_NEW)

            assertEquals(listOf("2401.00002"), activeIds())
        }
}
