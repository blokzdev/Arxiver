package dev.blokz.arxiver.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val workManager get() = WorkManager.getInstance(context)

        private val networked =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        /** Idempotent; called on every app start. */
        fun ensurePeriodicSync(intervalHours: Long = 6) {
            workManager.enqueueUniquePeriodicWork(
                FollowSyncWorker.UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FollowSyncWorker>(intervalHours, TimeUnit.HOURS)
                    .setConstraints(networked)
                    .build(),
            )
        }

        fun syncNow() {
            workManager.enqueueUniqueWork(
                FollowSyncWorker.UNIQUE_ONESHOT,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<FollowSyncWorker>()
                    .setConstraints(networked)
                    .build(),
            )
        }

        fun observeSyncRunning(): Flow<Boolean> =
            workManager.getWorkInfosForUniqueWorkFlow(FollowSyncWorker.UNIQUE_ONESHOT)
                .map {
                        infos ->
                    infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                }
    }
