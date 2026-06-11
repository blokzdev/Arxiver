package dev.blokz.arxiver

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.TaxonomySeeder
import dev.blokz.arxiver.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class ArxiverApplication : Application(), Configuration.Provider {
    @Inject lateinit var taxonomySeeder: TaxonomySeeder

    @Inject lateinit var dispatchers: DispatcherProvider

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var syncScheduler: SyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        CoroutineScope(SupervisorJob() + dispatchers.io).launch {
            taxonomySeeder.seed()
        }
        syncScheduler.ensurePeriodicSync()
    }
}
