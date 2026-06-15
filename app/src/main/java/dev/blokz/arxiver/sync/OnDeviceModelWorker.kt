package dev.blokz.arxiver.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.ml.ModelDownloader
import dev.blokz.arxiver.di.GemmaModel

/**
 * Downloads the Gemma 4 E2B `.litertlm` model (P1.2b). Bound to the unmetered +
 * storage-not-low constraint via [SyncScheduler.downloadOnDeviceModel]; the
 * ~1.87 GB fetch is SHA-256 verified by [ModelDownloader]. Retries on failure.
 */
@HiltWorker
class OnDeviceModelWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        @GemmaModel private val modelDownloader: ModelDownloader,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            when (modelDownloader.ensureDownloaded()) {
                is AppResult.Failure -> Result.retry()
                is AppResult.Success -> Result.success()
            }

        companion object {
            const val UNIQUE_ONESHOT = "ondevice_model_now"
        }
    }
