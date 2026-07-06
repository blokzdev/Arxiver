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
}
