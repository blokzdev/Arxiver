package dev.blokz.arxiver.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
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
                    // User-initiated → never digest (the user is already here).
                    .setInputData(workDataOf(EmbeddingWorker.SUPPRESS_DIGEST to true))
                    .build(),
            ).enqueue()
        }

        /** Settings change: replace the periodic cadence. */
        fun reschedulePeriodicSync(intervalHours: Long) {
            workManager.enqueueUniquePeriodicWork(
                FollowSyncWorker.UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<FollowSyncWorker>(intervalHours, TimeUnit.HOURS)
                    .setConstraints(networked)
                    .build(),
            )
            // Also reschedule the embedding/scoring pass — the digest + Today ranking ride its cadence, so a
            // user who tightens the interval should get them on the new cadence too (PA.1b fix).
            workManager.enqueueUniquePeriodicWork(
                EmbeddingWorker.UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<EmbeddingWorker>(intervalHours, TimeUnit.HOURS)
                    .setConstraints(unmetered)
                    .setInitialDelay(15, TimeUnit.MINUTES)
                    .build(),
            )
        }

        /** Queue drain for offline-queued Claude dispatches. */
        fun drainDispatches() {
            workManager.enqueueUniqueWork(
                DispatchWorker.UNIQUE,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DispatchWorker>()
                    .setConstraints(networked)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }

        fun embedNow() {
            workManager.enqueueUniqueWork(
                EmbeddingWorker.UNIQUE_ONESHOT,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<EmbeddingWorker>()
                    .setConstraints(unmetered)
                    // User-initiated → never digest.
                    .setInputData(workDataOf(EmbeddingWorker.SUPPRESS_DIGEST to true))
                    .build(),
            )
        }

        /** User-triggered Gemma 4 E2B model download (~2.6 GB) — unmetered only. */
        fun downloadOnDeviceModel() {
            workManager.enqueueUniqueWork(
                OnDeviceModelWorker.UNIQUE_ONESHOT,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<OnDeviceModelWorker>()
                    .setConstraints(unmetered)
                    .build(),
            )
        }

        /** User-triggered light-tier Qwen3-0.6B download (~614 MB; P-Atlas PA.3b) — separate unique work. */
        fun downloadLightModel() {
            workManager.enqueueUniqueWork(
                LightModelWorker.UNIQUE_ONESHOT,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LightModelWorker>()
                    .setConstraints(unmetered)
                    .build(),
            )
        }

        /** Cancel a unique work by name (background-tasks sheet). No-op if not running. */
        fun cancelUnique(uniqueName: String) {
            workManager.cancelUniqueWork(uniqueName)
        }

        // RUNNING only — never ENQUEUED. A one-shot waiting on retry backoff (or the
        // always-ENQUEUED periodic worker) is not "syncing now", and surfacing those as a
        // spinner left it spinning forever (a failing follow keeps the one-shot enqueued).
        fun observeSyncRunning(): Flow<Boolean> =
            workManager.getWorkInfosForUniqueWorkFlow(FollowSyncWorker.UNIQUE_ONESHOT)
                .map { infos ->
                    infos.any { it.state == WorkInfo.State.RUNNING }
                }
    }
