package dev.blokz.arxiver

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.TaxonomySeeder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class ArxiverApplication : Application() {
    @Inject lateinit var taxonomySeeder: TaxonomySeeder

    @Inject lateinit var dispatchers: DispatcherProvider

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        CoroutineScope(SupervisorJob() + dispatchers.io).launch {
            taxonomySeeder.seed()
        }
    }
}
