package dev.blokz.arxiver.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.database.entity.PaperFeedbackEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Durable relevance labels for the two-sided ranker (P4.0): upsert semantics, sign partitions, CASCADE. */
@RunWith(RobolectricTestRunner::class)
class PaperFeedbackDaoTest {
    private lateinit var db: ArxiverDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
    }

    @After
    fun tearDown() = db.close()

    private fun paper(id: String) =
        PaperEntity(
            id = id, latestVersion = 1, title = id, abstract = "", publishedAt = 0, updatedAt = 0,
            primaryCategory = "", authorsLine = "", comment = null, journalRef = null, doi = null,
            pdfUrl = "", citationCount = null, s2PaperId = null, source = "arxiv", fetchedAt = 0,
            embeddedAt = null, citationsSyncedAt = null,
        )

    private fun negative(
        id: String,
        source: String = PaperFeedbackEntity.SOURCE_DISMISS,
    ) = PaperFeedbackEntity(id, PaperFeedbackEntity.SIGNAL_NEGATIVE, source, createdAt = 0)

    private fun positive(id: String) =
        PaperFeedbackEntity(id, PaperFeedbackEntity.SIGNAL_POSITIVE, PaperFeedbackEntity.SOURCE_THUMB, createdAt = 1)

    @Test
    fun `signals partition into negative and positive id sets`() =
        runTest {
            val paperDao = db.paperDao()
            val feedback = db.paperFeedbackDao()
            listOf("a", "b", "c").forEach { paperDao.upsertPaper(paper(it)) }
            feedback.upsert(negative("a"))
            feedback.upsert(negative("b"))
            feedback.upsert(positive("c"))

            assertEquals(setOf("a", "b"), feedback.negativePaperIds().toSet())
            assertEquals(listOf("c"), feedback.positivePaperIds())
            assertEquals(PaperFeedbackEntity.SIGNAL_NEGATIVE, feedback.voteFor("a"))
            assertNull(feedback.voteFor("unknown"))
        }

    @Test
    fun `a later thumb upserts over an earlier dismiss (one row per paper)`() =
        runTest {
            val paperDao = db.paperDao()
            val feedback = db.paperFeedbackDao()
            paperDao.upsertPaper(paper("x"))

            feedback.upsert(negative("x")) // user dismissed it...
            feedback.upsert(positive("x")) // ...then thumbed it up

            assertEquals(PaperFeedbackEntity.SIGNAL_POSITIVE, feedback.voteFor("x"))
            assertEquals(emptyList(), feedback.negativePaperIds())
            assertEquals(listOf("x"), feedback.positivePaperIds())
        }

    @Test
    fun `clear removes a paper's label`() =
        runTest {
            val paperDao = db.paperDao()
            val feedback = db.paperFeedbackDao()
            paperDao.upsertPaper(paper("x"))
            feedback.upsert(positive("x"))

            feedback.clear("x")

            assertNull(feedback.voteFor("x"))
        }

    @Test
    fun `feedback is cascade-deleted with its paper`() =
        runTest {
            val paperDao = db.paperDao()
            val feedback = db.paperFeedbackDao()
            paperDao.upsertPaper(paper("x"))
            feedback.upsert(negative("x"))

            // Room enables PRAGMA foreign_keys; deleting the parent paper CASCADE-drops the label.
            db.openHelper.writableDatabase.execSQL("DELETE FROM papers WHERE id = 'x'")

            assertNull(feedback.voteFor("x"))
        }
}
