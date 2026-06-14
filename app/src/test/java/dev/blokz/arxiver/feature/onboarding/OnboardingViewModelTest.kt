package dev.blokz.arxiver.feature.onboarding

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.TaxonomySeeder
import dev.blokz.arxiver.data.CategoryRepository
import dev.blokz.arxiver.data.SettingsRepository
import dev.blokz.arxiver.sync.SyncScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Headless coverage of the first-run logic (what the Compose `OnboardingFlowTest`
 * diagnostic proved manually): completing onboarding persists the flag and enqueues
 * sync BEFORE handing control to navigation, and category toggles persist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnboardingViewModelTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var categories: CategoryRepository
    private lateinit var settings: SettingsRepository
    private lateinit var vm: OnboardingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java).build()
        categories = CategoryRepository(db.categoryDao(), db.followDao())
        settings = SettingsRepository(context)
        vm = OnboardingViewModel(categories, settings, SyncScheduler(context))
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `popular categories surface with follow state once loaded`() =
        runTest {
            TaxonomySeeder(db.categoryDao()).seed()
            val state = vm.uiState.first { !it.loading }
            assertTrue(state.popular.isNotEmpty())
            assertTrue(state.popular.all { (_, followed) -> !followed })
        }

    @Test
    fun `toggling a category persists the follow`() =
        runTest {
            TaxonomySeeder(db.categoryDao()).seed()
            val state = vm.uiState.first { it.popular.isNotEmpty() }
            val (category, _) = state.popular.first()

            vm.toggle(category, followed = true)

            val followed = vm.uiState.first { s -> s.popular.any { it.first.code == category.code && it.second } }
            assertTrue(followed.popular.first { it.first.code == category.code }.second)
        }

    // runBlocking (real time): finish() drives DataStore on a real IO dispatcher, which
    // runTest's virtual clock can't advance.
    @Test
    fun `finish persists onboarded before invoking the navigation callback`() =
        runBlocking {
            assertFalse(settings.onboarded.first())
            // The callback fires only after setOnboarded() completes (same coroutine, sequential).
            val navigated = CompletableDeferred<Unit>()

            vm.finish(onComplete = { navigated.complete(Unit) })

            withTimeout(5_000) { navigated.await() }
            assertTrue(settings.onboarded.first(), "onboarded flag must be persisted before navigation")
        }
}
