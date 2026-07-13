package dev.blokz.arxiver.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.FollowEntity
import dev.blokz.arxiver.core.database.entity.InboxItemEntity
import dev.blokz.arxiver.core.database.entity.PaperEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The PD.3b wiring: the cross-list/origin/window DAO query, and the repo's toggle-gate + daily-cache staleness. */
@RunWith(RobolectricTestRunner::class)
class TrendingRepositoryTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repo: TrendingRepository
    private val now = Instant.ofEpochMilli(1_700_000_000_000L)
    private val dayMs = 86_400_000L

    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.Unconfined
            override val default: CoroutineDispatcher = Dispatchers.Unconfined
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
        }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        settings = SettingsRepository(context)
        repo = TrendingRepository(db.inboxDao(), settings, dispatchers)
        runBlocking { settings.setTrendingEnabled(false) } // deterministic start (DataStore persists across a class)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedPaper(
        id: String,
        publishedAt: Long,
        categories: List<String>,
        followId: Long,
        origin: String = "arxiv",
    ) {
        db.paperDao().upsertPaperWithRelations(
            PaperEntity(
                id = id, latestVersion = 1, title = id, abstract = "", publishedAt = publishedAt,
                updatedAt = publishedAt, primaryCategory = categories.first(), authorsLine = "Author $id",
                comment = null, journalRef = null, doi = null, pdfUrl = "", citationCount = null,
                s2PaperId = null, source = "arxiv", fetchedAt = 0, embeddedAt = null, citationsSyncedAt = null,
                origin = origin,
            ),
            authors = listOf("Author $id"),
            categories = categories,
        )
        db.inboxDao().insertAll(listOf(InboxItemEntity(paperId = id, followId = followId, arrivedAt = 0)))
    }

    @Test
    fun `the window query returns cross-list rows, arXiv-scoped and inside the window only`() =
        runBlocking {
            val followId =
                db.followDao().insert(
                    FollowEntity(
                        type = FollowEntity.TYPE_CATEGORY,
                        value = "cs.LG",
                        label = "ML",
                        origin = "arxiv",
                        createdAt = 0,
                    ),
                )
            seedPaper(
                "A",
                now.toEpochMilli() - 3 * dayMs,
                listOf("cs.LG", "cs.CV"),
                followId,
            ) // in-window arXiv → 2 rows
            seedPaper(
                "B",
                now.toEpochMilli() - 3 * dayMs,
                listOf("q-bio.NC"),
                followId,
                origin = "chemrxiv",
            ) // non-arXiv → 0
            seedPaper("C", now.toEpochMilli() - 100 * dayMs, listOf("cs.RO"), followId) // out of window → 0

            val rows = db.inboxDao().trendingWindowRows(baselineFrom = now.toEpochMilli() - 70 * dayMs)

            assertEquals(2, rows.size, "only paper A's two cross-list rows")
            assertTrue(rows.all { it.paperId == "A" })
            assertEquals(setOf("cs.LG", "cs.CV"), rows.map { it.categoryCode }.toSet())
        }

    @Test
    fun `the toggle gates compute — off means no recompute and empty areas`() =
        runBlocking {
            // (DataStore persists across a class's methods, so assert the no-op + the toggle-gated flow, not null.)
            val before = settings.trendingCache.first()
            repo.recomputeIfStale(now)
            assertEquals(before, settings.trendingCache.first(), "no recompute while the opt-in is off")
            assertTrue(repo.observeAreas().first().isEmpty(), "off ⇒ empty areas regardless of any cache")
        }

    @Test
    fun `compute runs at most once per day (same-day recompute is a no-op)`() =
        runBlocking {
            settings.setTrendingEnabled(true)
            repo.recomputeIfStale(now)
            val firstCache = settings.trendingCache.first()
            assertNotNull(firstCache, "a cache is written even when nothing emerges")

            repo.recomputeIfStale(now) // same day
            assertEquals(firstCache, settings.trendingCache.first(), "same-day recompute must be a no-op")
        }
}
