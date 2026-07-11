package dev.blokz.arxiver.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.InboxItemEntity
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.database.entity.RelevanceModelEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The ranker-health card's `aboveCut` diagnostic must describe the SAME cut Today draws (P5.3 QW1). When a
 * calibration is persisted the runner derives the live cut from the `relevance_model` row (`PlattMap.scoreFor(0.5)`);
 * only an absent/uncalibrated row falls back to the legacy 0.55. These tests pin both branches against real DAOs.
 */
@RunWith(RobolectricTestRunner::class)
class RankerEvalRunnerTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var state: RankerEvalState
    private lateinit var runner: RankerEvalRunner

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        state = RankerEvalState()
        runner =
            RankerEvalRunner(
                paperFeedbackDao = db.paperFeedbackDao(),
                embeddingDao = db.embeddingDao(),
                inboxDao = db.inboxDao(),
                state = state,
                tuning = RankerTuning(),
                relevanceModelDao = db.relevanceModelDao(),
            )
    }

    @After
    fun tearDown() = db.close()

    /** A scored, rich-segment (abstract present) active-inbox paper — the population the card's distribution reads. */
    private suspend fun scoredInboxPaper(
        id: String,
        score: Double,
    ) {
        db.paperDao().upsertPaper(
            PaperEntity(
                id = id, latestVersion = 1, title = id, abstract = "a real abstract", publishedAt = 0,
                updatedAt = 0, primaryCategory = "", authorsLine = "", comment = null, journalRef = null,
                doi = null, pdfUrl = "", citationCount = null, s2PaperId = null, source = "arxiv",
                fetchedAt = 0, embeddedAt = 1, citationsSyncedAt = null,
            ),
        )
        db.inboxDao().insertAll(listOf(InboxItemEntity(paperId = id, followId = 1L, arrivedAt = 0)))
        db.inboxDao().setScore(id, score)
    }

    // Two scores that straddle the two regimes: both clear a calibrated 0.4 cut, both fall under the legacy 0.55.
    private suspend fun seedStraddlingInbox() {
        scoredInboxPaper("p1", 0.45)
        scoredInboxPaper("p2", 0.50)
    }

    @Test
    fun `aboveCut uses the persisted calibrated cut, not the legacy constant`() =
        runBlocking {
            // a=10,b=-4 ⇒ scoreFor(0.5) = 0.4; the same fixture TodayViewModelTest pins for the calibrated cut.
            db.relevanceModelDao().upsert(
                RelevanceModelEntity(
                    embeddingModel = EmbeddingWorker.MODEL_NAME,
                    calibrationA = 10.0,
                    calibrationB = -4.0,
                    shrinkageLambda = 0.0,
                    labelPositives = 60,
                    labelNegatives = 40,
                    fittedAt = 1L,
                ),
            )
            seedStraddlingInbox()

            // No labels ⇒ this pass fits nothing, but the published distribution reads the PREVIOUS (seeded) row.
            runner.run(EmbeddingWorker.RELEVANT_THRESHOLD)

            val rich = assertNotNull(state.latest.value?.richDistribution)
            assertEquals(2, rich.count)
            assertEquals(1.0, rich.aboveCut, 1e-9) // both 0.45 & 0.50 clear the calibrated 0.4 cut
        }

    @Test
    fun `aboveCut falls back to the legacy cut when no calibration is persisted`() =
        runBlocking {
            seedStraddlingInbox()

            runner.run(EmbeddingWorker.RELEVANT_THRESHOLD)

            val rich = assertNotNull(state.latest.value?.richDistribution)
            assertEquals(2, rich.count)
            assertEquals(0.0, rich.aboveCut, 1e-9) // 0.45 & 0.50 both fall under the legacy 0.55 cut
        }
}
