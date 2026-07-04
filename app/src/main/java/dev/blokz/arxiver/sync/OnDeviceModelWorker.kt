package dev.blokz.arxiver.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.ml.ModelDownloader
import dev.blokz.arxiver.core.ml.ModelState
import dev.blokz.arxiver.di.GemmaModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Downloads the Gemma 4 E2B `.litertlm` model (P1.2b). Bound to the unmetered +
 * storage-not-low constraint via [SyncScheduler.downloadOnDeviceModel]; the
 * ~2.59 GB fetch is SHA-256 verified by [ModelDownloader]. Retries on failure.
 *
 * Runs as a foreground service with a local progress notification (UX2.8) so the long download
 * survives backgrounding/Doze; the notification is on-device only (no telemetry).
 */
@HiltWorker
class OnDeviceModelWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        @GemmaModel private val modelDownloader: ModelDownloader,
        private val notifications: DownloadNotifications,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            coroutineScope {
                val title = R.string.bg_task_gemma_download
                val id = DownloadNotifications.GEMMA_NOTIFICATION_ID
                setForeground(notifications.foregroundInfo(title, progressPercent = 0, notificationId = id))
                val progress =
                    launch {
                        modelDownloader.state.collect { state ->
                            if (state is ModelState.Downloading) {
                                notifications.updateProgress(title, state.progressPercent, notificationId = id)
                            }
                        }
                    }
                val result = modelDownloader.ensureDownloaded()
                progress.cancel()
                when (result) {
                    is AppResult.Failure -> Result.retry()
                    is AppResult.Success -> {
                        notifications.notifyCompleted(
                            modelNameRes = R.string.ai_engine_gemma,
                            notificationId = DownloadNotifications.GEMMA_DONE_NOTIFICATION_ID,
                        )
                        Result.success()
                    }
                }
            }

        companion object {
            const val UNIQUE_ONESHOT = "ondevice_model_now"
        }
    }
