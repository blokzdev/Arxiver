package dev.blokz.arxiver.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.entity.InboxItemEntity
import dev.blokz.arxiver.core.database.entity.PaperEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Guards PF.3 unfollow cleanup: [dev.blokz.arxiver.core.database.dao.InboxDao.deleteByFollowId] is surgical. */
@RunWith(RobolectricTestRunner::class)
class InboxDaoTest {
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

    @Test
    fun `deleteByFollowId removes only that follow's inbox rows`() =
        runTest {
            val paperDao = db.paperDao()
            val inbox = db.inboxDao()
            // Inbox rows FK to papers (CASCADE) — seed the papers first.
            listOf("a", "b", "c").forEach { paperDao.upsertPaper(paper(it)) }
            inbox.insertAll(
                listOf(
                    InboxItemEntity(paperId = "a", followId = 1L, arrivedAt = 0),
                    InboxItemEntity(paperId = "b", followId = 1L, arrivedAt = 0),
                    InboxItemEntity(paperId = "c", followId = 2L, arrivedAt = 0),
                ),
            )

            inbox.deleteByFollowId(1L)

            val remaining = inbox.activePaperIds().toSet()
            assertEquals(setOf("c"), remaining, "only follow 2's row survives")
        }

    @Test
    fun `a paper surfaced by two follows keeps one inbox row (paper_id PK, IGNORE)`() =
        runTest {
            val paperDao = db.paperDao()
            val inbox = db.inboxDao()
            paperDao.upsertPaper(paper("x"))
            inbox.insertAll(listOf(InboxItemEntity(paperId = "x", followId = 1L, arrivedAt = 0)))
            // Same paper, different follow — IGNORE keeps the first-writer row (single row per paper).
            inbox.insertAll(listOf(InboxItemEntity(paperId = "x", followId = 2L, arrivedAt = 0)))
            assertEquals(listOf("x"), inbox.activePaperIds())
        }

    @Test
    fun `activeInboxTopK returns scored active rows at-or-above the cut, best-first, limited, ignoring digested`() =
        runTest {
            val paperDao = db.paperDao()
            val inbox = db.inboxDao()
            listOf("hi", "mid", "low", "dismissed", "unscored", "digested").forEach { paperDao.upsertPaper(paper(it)) }
            inbox.insertAll(
                listOf(
                    InboxItemEntity(paperId = "hi", followId = 1L, arrivedAt = 0, state = "new", score = 0.90),
                    InboxItemEntity(paperId = "mid", followId = 1L, arrivedAt = 0, state = "seen", score = 0.70),
                    // below the cut → excluded
                    InboxItemEntity(paperId = "low", followId = 1L, arrivedAt = 0, state = "new", score = 0.40),
                    // wrong state → excluded even though high score
                    InboxItemEntity(
                        paperId = "dismissed",
                        followId = 1L,
                        arrivedAt = 0,
                        state = "dismissed",
                        score = 0.95,
                    ),
                    // no score (cold start) → never a fake "likely relevant"
                    InboxItemEntity(paperId = "unscored", followId = 1L, arrivedAt = 0, state = "new", score = null),
                    // already digested → STILL shown (unlike eligibleDigest, the widget shows current best)
                    InboxItemEntity(
                        paperId = "digested",
                        followId = 1L,
                        arrivedAt = 0,
                        state = "new",
                        score = 0.80,
                        digestedAt = 123L,
                    ),
                ),
            )

            // Eligible (score >= 0.55, state new/seen): hi(0.90), digested(0.80), mid(0.70). Top-2 by score.
            assertEquals(listOf("hi", "digested"), inbox.activeInboxTopK(cut = 0.55, k = 2).map { it.paperId })
            // The digested row proves the widget query drops the digest's `digested_at IS NULL` filter.
            assertEquals(
                listOf("hi", "digested", "mid"),
                inbox.activeInboxTopK(cut = 0.55, k = 10).map { it.paperId },
            )
        }
}
