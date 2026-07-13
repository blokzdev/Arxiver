package dev.blokz.arxiver.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.FollowEntity
import dev.blokz.arxiver.core.database.entity.InboxItemEntity
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.model.ArxivCategory
import dev.blokz.arxiver.core.model.ArxivTaxonomy
import dev.blokz.arxiver.core.model.Source
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

/** Guards the PF.3 origin-aware follow lifecycle at the repository seam (create/unfollow/observe + inbox cleanup). */
@RunWith(RobolectricTestRunner::class)
class CategoryRepositoryTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var repo: CategoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        repo = CategoryRepository(db.categoryDao(), db.followDao(), db.inboxDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `follow writes the source origin, unfollow deletes only that origin`() =
        runBlocking {
            // Same category token followed on two sources — independent rows keyed by origin.
            repo.setCategoryFollowed("neuroscience", "Neuroscience", Source.BIORXIV, followed = true)
            repo.setCategoryFollowed("neuroscience", "Neuroscience", Source.MEDRXIV, followed = true)
            assertEquals(
                setOf("biorxiv", "medrxiv"),
                db.followDao().observeAll().first().map { it.origin }.toSet(),
            )

            repo.setCategoryFollowed("neuroscience", "Neuroscience", Source.BIORXIV, followed = false)
            val rows = db.followDao().observeAll().first()
            assertEquals(1, rows.size)
            assertEquals("medrxiv", rows.single().origin, "medRxiv follow of the same value survives")
        }

    @Test
    fun `the arXiv grid toggle is origin=arxiv and independent of a same-value non-arXiv follow`() =
        runBlocking {
            repo.setCategoryFollowed("cs.LG", "ML", Source.CHEMRXIV, followed = true)
            repo.setFollowed(ArxivCategory("cs.LG", "Machine Learning", ArxivTaxonomy.GROUP_CS), followed = true)
            val byOrigin = db.followDao().observeAll().first().associateBy { it.origin }
            assertTrue("arxiv" in byOrigin && "chemrxiv" in byOrigin, "both rows coexist under the (…,origin) index")
            // The arXiv-scoped grid query sees only the arXiv follow.
            assertEquals(listOf("cs.LG"), db.followDao().observeFollowedCategoryCodes().first())
        }

    @Test
    fun `unfollow removes the follow's inbox rows`() =
        runBlocking {
            repo.setCategoryFollowed("neuroscience", "Neuroscience", Source.BIORXIV, followed = true)
            val followId = db.followDao().find(FollowEntity.TYPE_CATEGORY, "neuroscience", "biorxiv")!!.id

            db.paperDao().upsertPaper(paper("biorxiv:10.1101/x"))
            db.inboxDao().insertAll(
                listOf(InboxItemEntity(paperId = "biorxiv:10.1101/x", followId = followId, arrivedAt = 0)),
            )
            assertEquals(1, db.inboxDao().activePaperIds().size)

            repo.setCategoryFollowed("neuroscience", "Neuroscience", Source.BIORXIV, followed = false)

            assertTrue(db.inboxDao().activePaperIds().isEmpty(), "the unfollowed feed's inbox row is cleaned")
            assertNull(db.followDao().find(FollowEntity.TYPE_CATEGORY, "neuroscience", "biorxiv"))
        }

    @Test
    fun `removeFollow drops an author follow (which setCategoryFollowed cannot) and its inbox rows`() =
        runBlocking {
            // An author follow — TYPE_AUTHOR, so the category-only setCategoryFollowed path could never remove it.
            val id =
                db.followDao().insert(
                    FollowEntity(
                        type = FollowEntity.TYPE_AUTHOR,
                        value = "Yann LeCun",
                        label = "Yann LeCun",
                        createdAt = 0,
                    ),
                )
            db.paperDao().upsertPaper(paper("arxiv:2401.00001"))
            db.inboxDao().insertAll(
                listOf(InboxItemEntity(paperId = "arxiv:2401.00001", followId = id, arrivedAt = 0)),
            )
            assertEquals(1, db.inboxDao().activePaperIds().size)

            repo.removeFollow(db.followDao().observeAll().first().single())

            assertTrue(db.followDao().observeAll().first().isEmpty(), "the author follow is removed")
            assertTrue(db.inboxDao().activePaperIds().isEmpty(), "its inbox row is cleaned in the same operation")
        }

    @Test
    fun `setAuthorFollowed writes a TYPE_AUTHOR follow pinned to origin=arxiv, and unfollow cleans its inbox`() =
        runBlocking {
            repo.setAuthorFollowed("Yann LeCun", followed = true)
            val follow = db.followDao().observeAll().first().single()
            assertEquals(FollowEntity.TYPE_AUTHOR, follow.type)
            assertEquals("Yann LeCun", follow.value)
            // Red line: author follows are ALWAYS origin=arxiv, else sync mis-routes to the whole-source browse.
            assertEquals("arxiv", follow.origin)
            assertTrue(follow.enabled)

            db.paperDao().upsertPaper(paper("arxiv:2401.00001"))
            db.inboxDao().insertAll(
                listOf(InboxItemEntity(paperId = "arxiv:2401.00001", followId = follow.id, arrivedAt = 0)),
            )
            assertEquals(1, db.inboxDao().activePaperIds().size)

            repo.setAuthorFollowed("Yann LeCun", followed = false)
            assertTrue(db.followDao().observeAll().first().isEmpty(), "the author follow is removed")
            assertTrue(db.inboxDao().activePaperIds().isEmpty(), "its inbox rows are cleaned on unfollow")
        }

    private fun paper(id: String) =
        PaperEntity(
            id = id, latestVersion = 1, title = id, abstract = "", publishedAt = 0, updatedAt = 0,
            primaryCategory = "", authorsLine = "", comment = null, journalRef = null, doi = null,
            pdfUrl = "", citationCount = null, s2PaperId = null, source = "arxiv", fetchedAt = 0,
            embeddedAt = null, citationsSyncedAt = null,
        )
}
