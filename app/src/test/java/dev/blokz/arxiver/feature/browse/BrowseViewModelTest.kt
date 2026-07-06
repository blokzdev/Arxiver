package dev.blokz.arxiver.feature.browse

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.TaxonomySeeder
import dev.blokz.arxiver.core.model.ArxivCategory
import dev.blokz.arxiver.core.model.ArxivTaxonomy
import dev.blokz.arxiver.data.CategoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BrowseViewModelTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var repo: CategoryRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                // Synchronous executors so the InvalidationTracker refresh can't race db.close() and
                // leak an "Illegal connection pointer" into the next test (memory
                // robolectric-room-sync-executors).
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        repo = CategoryRepository(db.categoryDao(), db.followDao(), db.inboxDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `groups the full taxonomy by top-level group`() =
        runTest {
            TaxonomySeeder(db.categoryDao()).seed()

            val state = BrowseViewModel(repo).uiState.first { it.groups.isNotEmpty() }

            assertEquals(ArxivTaxonomy.groups.size, state.groups.size)
            assertTrue(state.groups.any { it.name == ArxivTaxonomy.GROUP_CS })
        }

    @Test
    fun `follow state surfaces on the matching category`() =
        runTest {
            TaxonomySeeder(db.categoryDao()).seed()
            repo.setFollowed(ArxivCategory("cs.LG", "Machine Learning", ArxivTaxonomy.GROUP_CS), true)

            val state =
                BrowseViewModel(repo).uiState.first { s ->
                    s.groups.flatMap { it.categories }.any { it.followed }
                }
            val csLg = state.groups.flatMap { it.categories }.first { it.category.code == "cs.LG" }
            assertTrue(csLg.followed)
        }
}
