package dev.blokz.arxiver.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.InboxItemEntity
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ambient-digest fire logic (PA.1b): exactly-once accounting + every fatigue/permission gate, all provable
 * without a device (the actual notification is a fake here; posting is device-verified separately).
 */
@RunWith(RobolectricTestRunner::class)
class DigestRunnerTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var notifier: FakeDigestNotifier
    private lateinit var runner: DigestRunner

    private val now = 2_000_000_000_000L

    private class FakeDigestNotifier(var permitted: Boolean = true) : DigestNotifier {
        val posts = mutableListOf<Pair<Int, List<String>>>()

        override fun canPost() = permitted

        override fun notifyDigest(
            count: Int,
            titles: List<String>,
        ) {
            posts += count to titles
        }
    }

    @Before
    fun setUp() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db =
                Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                    .setQueryExecutor { it.run() }
                    .setTransactionExecutor { it.run() }
                    .build()
            settings = SettingsRepository(context)
            notifier = FakeDigestNotifier()
            runner = DigestRunner(db.inboxDao(), db.relevanceModelDao(), settings, notifier)
            runner.clock = { now }
            // Deterministic starting point (the DataStore file can persist across a class's methods).
            settings.setDigestEnabled(true)
            settings.setLastDigestPostedAt(0L)
        }

    @After
    fun tearDown() = db.close()

    /** A scored, recently-arrived, not-yet-digested inbox row with a real title. */
    private suspend fun eligibleRow(
        id: String,
        score: Double,
        arrivedAt: Long = now - 1000,
        digestedAt: Long? = null,
    ) {
        db.paperDao().upsertPaper(
            PaperEntity(
                id = id, latestVersion = 1, title = "Title $id", abstract = "a", publishedAt = 0, updatedAt = 0,
                primaryCategory = "cs.LG", authorsLine = "", comment = null, journalRef = null, doi = null,
                pdfUrl = "", citationCount = null, s2PaperId = null, source = "arxiv", fetchedAt = 0,
                embeddedAt = 1, citationsSyncedAt = null,
            ),
        )
        db.inboxDao().insertAll(
            listOf(
                InboxItemEntity(
                    paperId = id,
                    followId = 1L,
                    arrivedAt = arrivedAt,
                    score = score,
                    digestedAt = digestedAt,
                ),
            ),
        )
    }

    @Test
    fun `posts once, marks exactly the counted rows, and does not re-fire`() =
        runBlocking {
            eligibleRow("p1", 0.60)
            eligibleRow("p2", 0.70)
            eligibleRow("below", 0.40) // under the 0.55 legacy cut

            runner.maybePost(suppressed = false)

            assertEquals(1, notifier.posts.size)
            assertEquals(2, notifier.posts.single().first, "counts only the two above-cut rows")
            assertTrue(notifier.posts.single().second.first().startsWith("Title p2"), "best-first titles")
            assertEquals(now, settings.lastDigestPostedAt.first(), "the daily-cap cursor advanced")

            // Second pass: the two are now digested → nothing eligible → no re-fire.
            settings.setLastDigestPostedAt(0L) // defeat the daily cap so only the digested_at guard is under test
            runner.maybePost(suppressed = false)
            assertEquals(1, notifier.posts.size, "already-digested rows never re-fire (exactly-once)")
        }

    @Test
    fun `disabled, suppressed, and daily-capped passes post nothing and mark nothing`() =
        runBlocking {
            eligibleRow("p1", 0.9)

            settings.setDigestEnabled(false)
            runner.maybePost(suppressed = false)
            assertTrue(notifier.posts.isEmpty(), "disabled → no post")

            settings.setDigestEnabled(true)
            runner.maybePost(suppressed = true)
            assertTrue(notifier.posts.isEmpty(), "user-initiated (suppressed) → no post")

            settings.setLastDigestPostedAt(now) // within the min interval
            runner.maybePost(suppressed = false)
            assertTrue(notifier.posts.isEmpty(), "daily cap → no post")
        }

    @Test
    fun `no notification permission means no post AND no mark, so a later grant still sees the backlog`() =
        runBlocking {
            eligibleRow("p1", 0.9)
            notifier.permitted = false

            runner.maybePost(suppressed = false)
            assertTrue(notifier.posts.isEmpty())
            assertNull(settings.lastDigestPostedAt.first().takeIf { it != 0L }, "cursor NOT advanced")

            // Grant later → the row is still eligible and fires.
            notifier.permitted = true
            runner.maybePost(suppressed = false)
            assertEquals(1, notifier.posts.size, "the backlog was not swallowed")
        }
}
