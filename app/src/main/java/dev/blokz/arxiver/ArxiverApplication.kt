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
    }
}
