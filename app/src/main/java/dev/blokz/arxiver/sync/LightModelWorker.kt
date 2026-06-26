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
import dev.blokz.arxiver.di.QwenModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Downloads the light-tier Qwen3-0.6B `.litertlm` model (P-Atlas PA.3b). A sibling of
 * [OnDeviceModelWorker] bound to the same unmetered + storage-not-low constraint via
 * [SyncScheduler.downloadLightModel]; the ~614 MB fetch is SHA-256 verified by the `@QwenModel`
 * [ModelDownloader]. Separate unique work + downloader so it never collides with the Gemma download.
 *
 * Runs as a foreground service with a local progress notification (on-device only, no telemetry) so
 * the long download survives backgrounding/Doze. Retries on failure.
 */
@HiltWorker
class LightModelWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        @QwenModel private val modelDownloader: ModelDownloader,
        private val notifications: DownloadNotifications,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            coroutineScope {
                val title = R.string.bg_task_light_download
                setForeground(notifications.foregroundInfo(title, progressPercent = 0))
                val progress =
                    launch {
                        modelDownloader.state.collect { state ->
                            if (state is ModelState.Downloading) {
                                notifications.updateProgress(title, state.progressPercent)
                            }
                        }
                    }
                val result = modelDownloader.ensureDownloaded()
                progress.cancel()
                when (result) {
                    is AppResult.Failure -> Result.retry()
                    is AppResult.Success -> Result.success()
                }
            }

        companion object {
            const val UNIQUE_ONESHOT = "ondevice_light_model_now"
        }
    }
