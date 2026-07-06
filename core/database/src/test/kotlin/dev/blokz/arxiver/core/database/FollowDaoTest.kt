package dev.blokz.arxiver.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.entity.FollowEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guards the PF.3 origin-aware follow queries: unfollow deletes exactly one origin, the grid stays arXiv-only. */
@RunWith(RobolectricTestRunner::class)
class FollowDaoTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var dao: dev.blokz.arxiver.core.database.dao.FollowDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                // Synchronous executors: the InvalidationTracker refresh can't race db.close()
                // (memory robolectric-room-sync-executors).
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        dao = db.followDao()
    }

    @After
    fun tearDown() = db.close()

    private fun follow(
        value: String,
        origin: String,
    ) = FollowEntity(type = FollowEntity.TYPE_CATEGORY, value = value, label = value, origin = origin, createdAt = 0)

    @Test
    fun `unfollow deletes only the named origin's row`() =
        runTest {
            dao.insert(follow("neuroscience", "arxiv"))
            dao.insert(follow("neuroscience", "biorxiv"))

            dao.delete(FollowEntity.TYPE_CATEGORY, "neuroscience", "biorxiv")

            val remaining = dao.observeAll().first()
            assertEquals(1, remaining.size)
            assertEquals("arxiv", remaining.single().origin, "the arXiv follow of the same value must survive")
        }

    @Test
    fun `observeFollowedCategoryCodes is arXiv-scoped and the count is origin-agnostic`() =
        runTest {
            dao.insert(follow("neuroscience", "biorxiv"))
            dao.insert(follow("fields/16", "chemrxiv"))
            // No arXiv follows yet: the grid sees nothing, but the origin-agnostic count sees both.
            assertTrue(dao.observeFollowedCategoryCodes().first().isEmpty())
            assertEquals(2, dao.observeEnabledFollowCount().first())

            dao.insert(follow("cs.LG", "arxiv"))
            assertEquals(listOf("cs.LG"), dao.observeFollowedCategoryCodes().first())
            assertEquals(3, dao.observeEnabledFollowCount().first())
        }

    @Test
    fun `find resolves the row for a given (type, value, origin)`() =
        runTest {
            dao.insert(follow("neuroscience", "biorxiv"))
            assertEquals("biorxiv", dao.find(FollowEntity.TYPE_CATEGORY, "neuroscience", "biorxiv")?.origin)
            assertNull(dao.find(FollowEntity.TYPE_CATEGORY, "neuroscience", "arxiv"))
        }
}
