package dev.blokz.arxiver

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.TaxonomySeeder
import dev.blokz.arxiver.sync.SyncScheduler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class ArxiverApplication : Application(), Configuration.Provider {
    @Inject lateinit var taxonomySeeder: TaxonomySeeder

    @Inject lateinit var dispatchers: DispatcherProvider

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var syncScheduler: SyncScheduler

    @Inject lateinit var settingsRepository: dev.blokz.arxiver.data.SettingsRepository

    // Injected unconditionally (it only holds DAO refs; does nothing until seed() is called). The seed itself is
    // fired ONLY under the ENABLE_TEST_CORPUS gate below — true solely on the benchmark variants (P-Prove PP.3).
    @Inject lateinit var testCorpusSeeder: dev.blokz.arxiver.bench.TestCorpusSeeder

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // Startup side-effects run fire-and-forget on an app-lifetime scope. Route any
        // failure to Timber instead of the uncaught handler: a seed/sync failure is
        // non-fatal (seed is idempotent, sync reschedules), and an unhandled throw here
        // otherwise escalates to a crash — including the DB-teardown race that pollutes
        // Robolectric unit tests (a cancelled seed transaction throwing on a closed
        // connection surfaced as kotlinx-coroutines-test UncaughtExceptionsBeforeTest).
        val startupExceptionHandler =
            CoroutineExceptionHandler { _, throwable ->
                Timber.e(throwable, "Application startup task failed")
            }
        val startupScope = CoroutineScope(SupervisorJob() + dispatchers.io + startupExceptionHandler)
        startupScope.launch {
            taxonomySeeder.seed()
        }
        startupScope.launch {
            syncScheduler.ensurePeriodicSync(settingsRepository.syncIntervalHours.first().toLong())
        }
        // Benchmark-only: seed the deterministic perf corpus so the Macrobenchmark suites measure a real feed.
        // ENABLE_TEST_CORPUS is compile-time false in release AND debug, so this is dead code off the benchmark
        // variants — release never touches the production DB (P-Prove PP.3).
        if (BuildConfig.ENABLE_TEST_CORPUS) {
            startupScope.launch { testCorpusSeeder.seed() }
        }
    }
}
