package dev.blokz.arxiver.core.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Real system-Nano availability via the ML Kit GenAI Prompt API
 * (`docs/reference/on-device-ai.md`). On devices without AICore/Nano,
 * `checkStatus()` returns UNAVAILABLE and the platform degrades to Gemma/cloud.
 */
class MlKitNanoAvailability(
    private val clientProvider: () -> GenerativeModel = { Generation.getClient() },
) : NanoAvailability {
    override suspend fun status(): NanoStatus =
        when (clientProvider().checkStatus()) {
            FeatureStatus.AVAILABLE -> NanoStatus.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> NanoStatus.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> NanoStatus.DOWNLOADING
            else -> NanoStatus.UNAVAILABLE
        }

    override fun download(): Flow<NanoDownloadProgress> =
        flow {
            var total = 0L
            clientProvider().download().collect { status ->
                emit(
                    when (status) {
                        is DownloadStatus.DownloadStarted -> {
                            total = status.bytesToDownload
                            NanoDownloadProgress.Downloading(0)
                        }
                        is DownloadStatus.DownloadProgress ->
                            NanoDownloadProgress.Downloading(
                                if (total > 0) (status.totalBytesDownloaded * 100 / total).toInt() else null,
                            )
                        is DownloadStatus.DownloadCompleted -> NanoDownloadProgress.Done
                        is DownloadStatus.DownloadFailed -> NanoDownloadProgress.Failed(status.toString())
                        else -> NanoDownloadProgress.Downloading(null)
                    },
                )
            }
        }
}
