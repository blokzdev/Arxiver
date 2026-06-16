package dev.blokz.arxiver.sync

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.blokz.arxiver.core.ml.ModelDownloader
import dev.blokz.arxiver.core.ml.ModelState
import dev.blokz.arxiver.di.GemmaModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The kinds of background work the user can observe. Also the row identity in the status sheet. */
enum class TaskKind { GEMMA_DOWNLOAD, EMBEDDING_MODEL_DOWNLOAD, FOLLOW_SYNC, EMBEDDING }

sealed interface TaskState {
    /** [progress] in 0f..1f, or null for indeterminate (a worker we only know is RUNNING). */
    data class Running(val progress: Float?) : TaskState

    data object Failed : TaskState
}

data class BackgroundTask(
    val kind: TaskKind,
    val state: TaskState,
    val cancellable: Boolean,
)

/**
 * Single source of truth for "what's running in the background" (UX2.7). Merges the two model
 * downloaders' [ModelState] flows with WorkManager RUNNING state for sync/embedding into one
 * `Flow<List<BackgroundTask>>` the UI renders. Local-only — observes on-device state, sends nothing.
 */
@Singleton
class BackgroundTaskMonitor
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @GemmaModel private val gemmaDownloader: ModelDownloader,
        private val embeddingModelDownloader: ModelDownloader,
    ) {
        private val workManager = WorkManager.getInstance(context)

        val tasks: Flow<List<BackgroundTask>> =
            combine(
                modelTask(gemmaDownloader, TaskKind.GEMMA_DOWNLOAD),
                modelTask(embeddingModelDownloader, TaskKind.EMBEDDING_MODEL_DOWNLOAD),
                runningTask(FollowSyncWorker.UNIQUE_ONESHOT, TaskKind.FOLLOW_SYNC),
                runningTask(EmbeddingWorker.UNIQUE_ONESHOT, TaskKind.EMBEDDING),
            ) { gemma, embedModel, sync, embed ->
                listOfNotNull(gemma, embedModel, sync, embed)
            }

        private fun modelTask(
            downloader: ModelDownloader,
            kind: TaskKind,
        ): Flow<BackgroundTask?> = downloader.state.map { modelDownloadTask(it, kind) }

        // RUNNING only (mirrors observeSyncRunning): an enqueued/retry-backoff worker isn't "active".
        private fun runningTask(
            uniqueName: String,
            kind: TaskKind,
        ): Flow<BackgroundTask?> =
            workManager.getWorkInfosForUniqueWorkFlow(uniqueName).map { infos ->
                runningWorkTask(infos.any { it.state == WorkInfo.State.RUNNING }, kind)
            }
    }

/** A download surfaces only while in flight or failed; Ready/NotDownloaded show nothing. Pure for tests. */
internal fun modelDownloadTask(
    state: ModelState,
    kind: TaskKind,
): BackgroundTask? =
    when (state) {
        is ModelState.Downloading ->
            BackgroundTask(kind, TaskState.Running(state.progressPercent / 100f), cancellable = true)
        is ModelState.Failed -> BackgroundTask(kind, TaskState.Failed, cancellable = false)
        else -> null
    }

internal fun runningWorkTask(
    isRunning: Boolean,
    kind: TaskKind,
): BackgroundTask? = if (isRunning) BackgroundTask(kind, TaskState.Running(null), cancellable = true) else null

/** Maps a task kind to the WorkManager unique name to cancel. bge download rides the embedding worker. */
internal fun uniqueWorkNameFor(kind: TaskKind): String =
    when (kind) {
        TaskKind.FOLLOW_SYNC -> FollowSyncWorker.UNIQUE_ONESHOT
        TaskKind.EMBEDDING, TaskKind.EMBEDDING_MODEL_DOWNLOAD -> EmbeddingWorker.UNIQUE_ONESHOT
        TaskKind.GEMMA_DOWNLOAD -> OnDeviceModelWorker.UNIQUE_ONESHOT
    }
