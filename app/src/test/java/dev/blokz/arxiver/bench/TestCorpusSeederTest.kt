package dev.blokz.arxiver.bench

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.FollowEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The benchmark corpus seeder (PP.3a), provable without a device on real in-memory Room DAOs (so FK ordering and
 * the IGNORE-on-conflict follow path are exercised for real). The `transaction` lambda is a pass-through here; the
 * production provider wraps `db.withTransaction`.
 */
@RunWith(RobolectricTestRunner::class)
class TestCorpusSeederTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var seeder: TestCorpusSeeder

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        seeder =
            TestCorpusSeeder(
                paperDao = db.paperDao(),
                embeddingDao = db.embeddingDao(),
                followDao = db.followDao(),
                inboxDao = db.inboxDao(),
                transaction = { block -> block() },
            )
    }

    @After
    fun tearDown() = db.close()

    private fun ids(n: Int) = (0 until n).map { "p%06d".format(it) }.toSet()

    @Test
    fun `seed populates papers, embeddings, an enabled follow, and FK-valid inbox rows`() =
        runBlocking {
            seeder.seed(n = 8)

            assertEquals(8, db.embeddingDao().count(), "one embedding per paper")
            assertNotNull(db.embeddingDao().byPaperId("p000000"), "sentinel embedding present")
            assertEquals(1, db.followDao().enabledFollows().size, "exactly one enabled seed follow")
            // JOIN papers ON i.paper_id — a row only appears if its parent paper exists (FK order proven).
            assertEquals(ids(8), db.inboxDao().activePaperIds().toSet(), "8 inbox rows, ids p000000..p000007")
            // JOIN follows ON f.id = i.follow_id WHERE enabled — proves every inbox row links the seeded follow.
            assertEquals(
                ids(8),
                db.inboxDao().activeIdsFromEnabledFollows().toSet(),
                "inbox linked to the enabled follow",
            )
        }

    @Test
    fun `inbox scores ramp across the relevance cut so both Today sections render`() =
        runBlocking {
            seeder.seed(n = 8)
            val top = db.inboxDao().scoreFor("p000000")!!
            val bottom = db.inboxDao().scoreFor("p000007")!!
            assertTrue(top >= 0.55, "highest-ranked seeded row is above the 0.55 cut (was $top)")
            assertTrue(bottom < 0.55, "lowest-ranked seeded row is below the cut (was $bottom)")
            assertTrue(top > bottom, "strictly descending ramp")
        }

    @Test
    fun `re-seeding is a no-op — the sentinel short-circuits`() =
        runBlocking {
            seeder.seed(n = 8)
            seeder.seed(n = 8)
            assertEquals(8, db.embeddingDao().count(), "no duplicate embeddings")
            assertEquals(1, db.followDao().enabledFollows().size, "no duplicate follow")
            assertEquals(8, db.inboxDao().activePaperIds().size, "no duplicate inbox rows")
        }

    @Test
    fun `a pre-existing follow is resolved via find, not re-inserted`() =
        runBlocking {
            // Same identity (type, value, origin) as the seeder's SEED_FOLLOW ⇒ its IGNORE insert returns -1L and
            // the seeder must resolve the id via find() rather than crash or dangle a bad FK.
            db.followDao().insert(
                FollowEntity(
                    type = FollowEntity.TYPE_CATEGORY,
                    value = "cs.LG",
                    label = "pre-existing",
                    createdAt = 1L,
                    enabled = true,
                    origin = "arxiv",
                ),
            )
            seeder.seed(n = 4)
            assertEquals(1, db.followDao().enabledFollows().size, "IGNORE-dedup: no second follow")
            assertEquals(4, db.inboxDao().activeIdsFromEnabledFollows().size, "inbox linked to the resolved follow id")
        }
}
