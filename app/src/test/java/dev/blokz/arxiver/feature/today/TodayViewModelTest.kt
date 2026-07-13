package dev.blokz.arxiver.feature.today

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.turbine.test
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.InboxItemEntity
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.entity.PaperFeedbackEntity
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.data.CategoryRepository
import dev.blokz.arxiver.data.InboxRepository
import dev.blokz.arxiver.data.LibraryRepository
import dev.blokz.arxiver.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TodayViewModelTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var inbox: InboxRepository
    private lateinit var library: LibraryRepository
    private lateinit var categories: CategoryRepository
    private lateinit var vm: TodayViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Idempotent across test classes in a shared JVM — re-init throws otherwise.
        runCatching { WorkManagerTestInitHelper.initializeTestWorkManager(context) }
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                // Synchronous executors: the InvalidationTracker background refresh can otherwise race
                // db.close() (Robolectric "Illegal connection pointer"), and DB-write continuations
                // resume off-thread and race assertions. Direct executors make Room deterministic here.
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        library = LibraryRepository(db.libraryDao(), db.inboxDao())
        inbox = InboxRepository(db.inboxDao(), library, db.paperFeedbackDao())
        categories = CategoryRepository(db.categoryDao(), db.followDao(), db.inboxDao())
        val dispatchers =
            object : dev.blokz.arxiver.core.common.DispatcherProvider {
                override val io = kotlinx.coroutines.Dispatchers.Unconfined
                override val default = kotlinx.coroutines.Dispatchers.Unconfined
                override val main = kotlinx.coroutines.Dispatchers.Unconfined
            }
        val trending =
            dev.blokz.arxiver.data.TrendingRepository(
                db.inboxDao(),
                dev.blokz.arxiver.data.SettingsRepository(context),
                dispatchers,
            )
        vm = TodayViewModel(inbox, SyncScheduler(context), library, categories, db.relevanceModelDao(), trending)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun seedInbox(id: String) {
        val p =
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
        db.paperDao().upsertPaperWithRelations(p.toEntity(), p.authors, p.categories)
        db.inboxDao().insertAll(
            listOf(InboxItemEntity(paperId = id, followId = 1L, arrivedAt = 1L, state = InboxItemEntity.STATE_NEW)),
        )
    }

    @Test
    fun `inbox items surface in state`() =
        runTest {
            seedInbox("2401.00001")
            vm.uiState.test {
                val s = awaitGreedy { it.items.size == 1 }
                assertEquals("2401.00001", s.items.single().paper.id.value)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `save triages to library and emits a SAVED event`() =
        runTest {
            seedInbox("2401.00001")
            val item = inbox.observeInbox().first().single()

            vm.save(item)

            assertTrue(library.observeIsSaved("2401.00001").first())
            val event = vm.triageEvent.first { it != null }!!
            assertEquals(TriageKind.SAVED, event.kind)
            // Saved paper leaves the active inbox.
            assertFalse(inbox.observeInbox().first().any { it.paper.id.value == "2401.00001" })
        }

    @Test
    fun `undo of a save restores the inbox row and unsaves`() =
        runTest {
            seedInbox("2401.00001")
            val item = inbox.observeInbox().first().single()
            vm.save(item)
            val event = vm.triageEvent.first { it != null }!!

            vm.undo(event)

            assertFalse(library.observeIsSaved("2401.00001").first())
            assertTrue(inbox.observeInbox().first().any { it.paper.id.value == "2401.00001" })
        }

    @Test
    fun `dismiss writes a durable negative label and undo clears it`() =
        runTest {
            seedInbox("2401.00001")
            val item = inbox.observeInbox().first().single()

            vm.dismiss(item)

            assertEquals(
                PaperFeedbackEntity.SIGNAL_NEGATIVE,
                db.paperFeedbackDao().voteFor("2401.00001"),
                "dismiss snapshots a durable negative",
            )
            assertFalse(
                inbox.observeInbox().first().any { it.paper.id.value == "2401.00001" },
                "dismissed leaves the inbox",
            )

            val event = vm.triageEvent.first { it != null }!!
            assertEquals(TriageKind.DISMISSED, event.kind)
            vm.undo(event)

            assertNull(db.paperFeedbackDao().voteFor("2401.00001"), "undo clears the dismiss label")
            assertTrue(inbox.observeInbox().first().any { it.paper.id.value == "2401.00001" }, "the row is restored")
        }

    @Test
    fun `a relevance thumb toggles the durable label and surfaces on the row`() =
        runTest {
            seedInbox("2401.00001")
            val item = inbox.observeInbox().first().single()

            vm.relevanceVote(item, up = true)
            assertEquals(PaperFeedbackEntity.SIGNAL_POSITIVE, db.paperFeedbackDao().voteFor("2401.00001"))
            assertEquals(1, inbox.observeInbox().first().single().vote, "the up-vote surfaces on the row")

            vm.relevanceVote(item, up = true) // tapping the same direction clears it
            assertNull(db.paperFeedbackDao().voteFor("2401.00001"))
            assertNull(inbox.observeInbox().first().single().vote)

            vm.relevanceVote(item, up = false)
            assertEquals(PaperFeedbackEntity.SIGNAL_NEGATIVE, db.paperFeedbackDao().voteFor("2401.00001"))
            // A thumb-down keeps the paper in the inbox (soft signal, not a removal).
            assertTrue(inbox.observeInbox().first().any { it.paper.id.value == "2401.00001" })
        }

    @Test
    fun `weekly review selection unions recent library adds and top inbox`() =
        runTest {
            seedInbox("2401.00001")
            // A recently-added library paper.
            db.paperDao().upsertPaperWithRelations(
                Paper(
                    ref = ArxivRef(ArxivId("2402.00002")),
                    latestVersion = 1,
                    title = "Lib",
                    abstract = "A",
                    publishedAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                    primaryCategory = "cs.LG",
                    categories = listOf("cs.LG"),
                    authors = listOf("A"),
                ).toEntity(),
                listOf("A"),
                listOf("cs.LG"),
            )
            db.libraryDao().upsertEntry(
                LibraryEntryEntity(paperId = "2402.00002", addedAt = Instant.now().toEpochMilli()),
            )

            vm.uiState.test {
                awaitGreedy { it.items.isNotEmpty() }
                val selection = vm.weeklyReviewSelection()
                assertTrue("2401.00001" in selection)
                assertTrue("2402.00002" in selection)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- P5.3: the calibrated threshold ---

    @Test
    fun `no relevance model means EXACTLY the legacy 0_55 threshold`() =
        runTest {
            vm.uiState.test {
                val state = awaitItem()
                assertEquals(LEGACY_RELEVANT_THRESHOLD, state.relevantThreshold, 1e-12)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a fitted calibration moves the threshold to its p=0_5 point`() =
        runTest {
            // a=10, b=-4  =>  scoreFor(0.5) = 4/10 = 0.4
            db.relevanceModelDao().upsert(
                dev.blokz.arxiver.core.database.entity.RelevanceModelEntity(
                    embeddingModel = "bge-test",
                    calibrationA = 10.0,
                    calibrationB = -4.0,
                    shrinkageLambda = 0.0,
                    labelPositives = 40,
                    labelNegatives = 20,
                    fittedAt = 1L,
                ),
            )
            vm.uiState.test {
                val calibrated = awaitGreedy { kotlin.math.abs(it.relevantThreshold - 0.4) < 1e-9 }
                assertEquals(0.4, calibrated.relevantThreshold, 1e-9)
                cancelAndIgnoreRemainingEvents()
            }
        }
}

private suspend fun <T> app.cash.turbine.ReceiveTurbine<T>.awaitGreedy(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}
