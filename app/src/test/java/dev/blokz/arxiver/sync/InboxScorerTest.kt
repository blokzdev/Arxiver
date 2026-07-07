package dev.blokz.arxiver.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.FollowEntity
import dev.blokz.arxiver.core.database.entity.InboxItemEntity
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.database.entity.PaperFeedbackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.sqrt
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two-sided inbox scoring (P4.1) against real Room DAOs — no embedding/download pipeline needed.
 * Covers: dismiss demotes similar papers, follows cold-start yields scores (not recency-null),
 * a truly empty profile stays null, and a stale-model embedding is skipped without crashing.
 */
@RunWith(RobolectricTestRunner::class)
class InboxScorerTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var scorer: InboxScorer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        scorer = InboxScorer(db.libraryDao(), db.inboxDao(), db.embeddingDao(), db.paperFeedbackDao())
    }

    @After
    fun tearDown() = db.close()

    // --- fixtures ---

    private fun unit(vararg v: Float): FloatArray {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / norm }
    }

    /** Cluster axes in 4-space: A = +x (interests), B = +z (dislikes). */
    private fun nearA(i: Int) = unit(1f, 0.01f * i, 0f, 0f)

    private fun nearB(i: Int) = unit(0f, 0.01f * i, 1f, 0f)

    private fun paperRow(id: String) =
        PaperEntity(
            id = id, latestVersion = 1, title = id, abstract = "", publishedAt = 0, updatedAt = 0,
            primaryCategory = "", authorsLine = "", comment = null, journalRef = null, doi = null,
            pdfUrl = "", citationCount = null, s2PaperId = null, source = "arxiv", fetchedAt = 0,
            embeddedAt = null, citationsSyncedAt = null,
        )

    private suspend fun embed(
        id: String,
        vector: FloatArray,
        model: String = EmbeddingWorker.MODEL_NAME,
    ) {
        db.paperDao().upsertPaper(paperRow(id))
        db.embeddingDao().upsert(
            PaperEmbeddingEntity(
                paperId = id,
                vector = PaperEmbeddingEntity.floatsToBlob(vector),
                model = model,
                dim = vector.size,
            ),
        )
    }

    private suspend fun saveToLibrary(id: String) =
        db.libraryDao().upsertEntry(
            LibraryEntryEntity(paperId = id, addedAt = 0),
        )

    private suspend fun inbox(
        id: String,
        followId: Long,
    ) = db.inboxDao().insertAll(listOf(InboxItemEntity(paperId = id, followId = followId, arrivedAt = 0)))

    private suspend fun scores(): Map<String, Double?> =
        db.inboxDao().observeActiveInbox().first().associate { it.paper.id to it.score }

    // --- tests ---

    @Test
    fun `a dismiss-similar paper is demoted below a save-similar one`() =
        runBlocking {
            // 10 saved papers define the positive interest (cluster A).
            repeat(10) {
                embed("lib$it", nearA(it))
                saveToLibrary("lib$it")
            }
            // 3 dismissed papers define the negative (cluster B), durable in paper_feedback.
            repeat(3) {
                embed("neg$it", nearB(it))
                db.paperFeedbackDao().upsert(
                    PaperFeedbackEntity(
                        "neg$it",
                        PaperFeedbackEntity.SIGNAL_NEGATIVE,
                        PaperFeedbackEntity.SOURCE_DISMISS,
                        0,
                    ),
                )
            }
            // Two papers to score: one like the saves, one like the dismisses.
            embed("probeA", nearA(99))
            inbox("probeA", followId = 1L)
            embed("probeB", nearB(99))
            inbox("probeB", followId = 1L)

            scorer.scoreInbox()

            val s = scores()
            val a = assertNotNull(s["probeA"])
            val b = assertNotNull(s["probeB"])
            assertTrue(a > b, "the save-like paper outranks the dismiss-like paper ($a vs $b)")
            assertTrue(a > 0.55, "save-like clears 'Likely relevant'")
            assertTrue(b < 0.55, "dismiss-like is pushed out of 'Likely relevant'")
        }

    @Test
    fun `cold start seeds from enabled follows so a thin-library user gets scores`() =
        runBlocking {
            // No library at all; interest must come from the enabled follow's surfaced papers.
            val followId =
                db.followDao().insert(
                    FollowEntity(type = "category", value = "cs.LG", label = "ML", createdAt = 0),
                )
            repeat(12) {
                embed("seed$it", nearA(it))
                inbox("seed$it", followId = followId)
            }

            scorer.scoreInbox()

            val s = scores()
            assertTrue(s.values.all { it != null }, "every follows-surfaced paper is scored, not left to recency")
        }

    @Test
    fun `a truly empty profile leaves scores null (and a disabled follow does not seed)`() =
        runBlocking {
            // A DISABLED follow's leftover rows must not seed the model.
            val disabled =
                db.followDao().insert(
                    FollowEntity(type = "category", value = "cs.LG", label = "ML", createdAt = 0, enabled = false),
                )
            repeat(12) {
                embed("z$it", nearA(it))
                inbox("z$it", followId = disabled)
            }

            scorer.scoreInbox()

            assertTrue(
                scores().values.all { it == null },
                "no library + only disabled follows => recency (null scores)",
            )
        }

    @Test
    fun `a stale-model embedding is skipped without crashing`() =
        runBlocking {
            repeat(10) {
                embed("lib$it", nearA(it))
                saveToLibrary("lib$it")
            }
            embed("stale", nearA(1), model = "some-old-model")
            inbox("stale", followId = 1L)
            embed("fresh", nearA(2))
            inbox("fresh", followId = 1L)

            scorer.scoreInbox()

            val s = scores()
            assertNotNull(s["fresh"], "scoring continues past the skipped paper")
            assertNull(s["stale"], "a wrong-model vector is skipped (guards a dim-mismatch crash)")
        }
}
