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

        /** Embedding work (incl. one-time 34MB model fetch) sticks to unmetered networks. */
        private val unmetered =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresStorageNotLow(true)
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
            workManager.enqueueUniquePeriodicWork(
                CitationSyncWorker.UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CitationSyncWorker>(24, TimeUnit.HOURS)
                    .setConstraints(networked)
                    .setInitialDelay(2, TimeUnit.HOURS)
                    .build(),
            )
            workManager.enqueueUniquePeriodicWork(
                EmbeddingWorker.UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<EmbeddingWorker>(intervalHours, TimeUnit.HOURS)
                    .setConstraints(unmetered)
                    .setInitialDelay(15, TimeUnit.MINUTES)
                    .build(),
            )
        }

        /** Manual refresh: follow sync, then embedding/triage refresh. */
        fun syncNow() {
            workManager.beginUniqueWork(
                FollowSyncWorker.UNIQUE_ONESHOT,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<FollowSyncWorker>()
                    .setConstraints(networked)
                    .build(),
            ).then(
                OneTimeWorkRequestBuilder<EmbeddingWorker>()
                    .setConstraints(unmetered)
                    .build(),
            ).enqueue()
        }

        fun embedNow() {
            workManager.enqueueUniqueWork(
                EmbeddingWorker.UNIQUE_ONESHOT,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<EmbeddingWorker>()
                    .setConstraints(unmetered)
                    .build(),
            )
        }

        fun observeSyncRunning(): Flow<Boolean> =
            workManager.getWorkInfosForUniqueWorkFlow(FollowSyncWorker.UNIQUE_ONESHOT)
                .map { infos ->
                    infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                }
    }
