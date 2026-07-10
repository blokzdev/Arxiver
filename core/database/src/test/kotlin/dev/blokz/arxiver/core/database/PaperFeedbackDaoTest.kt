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
    // --- P5.1: labeledExamples() — the eval's label feed ---

    private suspend fun embeddedPaper(
        id: String,
        abstract: String = "An abstract.",
        model: String = "bge-test",
        embedded: Boolean = true,
    ) {
        db.paperDao().upsertPaper(paper(id).copy(abstract = abstract, embeddedAt = if (embedded) 1L else null))
        if (embedded) {
            db.embeddingDao().upsert(
                dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity(
                    paperId = id,
                    vector =
                        dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity.floatsToBlob(
                            FloatArray(4) { 0.5f },
                        ),
                    model = model,
                    dim = 4,
                ),
            )
        }
    }

    private suspend fun save(id: String) =
        db.libraryDao().upsertEntry(
            dev.blokz.arxiver.core.database.entity.LibraryEntryEntity(paperId = id, addedAt = 7L),
        )

    @Test
    fun `library saves are positives even without a feedback row`() =
        runTest {
            // The defect the refinement caught: saves are the ranker's dominant positive and never write a
            // feedback row — a feedback-only read would report "insufficient data" forever.
            embeddedPaper("s1")
            save("s1")

            val rows = db.paperFeedbackDao().labeledExamples("bge-test")

            assertEquals(1, rows.size)
            assertEquals(true, rows.single().positive)
            assertEquals("save", rows.single().labelSource)
            assertEquals(7L, rows.single().createdAt)
        }

    @Test
    fun `a saved-then-dismissed paper counts positive exactly once`() =
        runTest {
            embeddedPaper("s2")
            save("s2")
            db.paperFeedbackDao().upsert(negative("s2"))

            val rows = db.paperFeedbackDao().labeledExamples("bge-test")

            assertEquals(1, rows.size, "positive wins — the live scorer's dedupe rule")
            assertEquals(true, rows.single().positive)
        }

    @Test
    fun `a thumbed-up saved paper is one positive, not two`() =
        runTest {
            embeddedPaper("s3")
            save("s3")
            db.paperFeedbackDao().upsert(positive("s3"))

            assertEquals(1, db.paperFeedbackDao().labeledExamples("bge-test").size)
        }

    @Test
    fun `a dismiss surfaces as a negative and a blank abstract marks the title-only segment`() =
        runTest {
            embeddedPaper("s4", abstract = "") // the census case: SSRN strips abstracts
            db.paperFeedbackDao().upsert(negative("s4"))

            val row = db.paperFeedbackDao().labeledExamples("bge-test").single()
            assertEquals(false, row.positive)
            assertEquals("dismiss", row.labelSource)
            assertEquals(true, row.titleOnly)
        }

    @Test
    fun `stale-model vectors and never-embedded papers never reach a fold`() =
        runTest {
            embeddedPaper("s5", model = "old-model")
            save("s5")
            embeddedPaper("s6", embedded = false)
            save("s6")

            assertEquals(0, db.paperFeedbackDao().labeledExamples("bge-test").size)
        }
}
