package dev.blokz.arxiver.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Progress of a system-Nano feature download (provider-neutral). */
sealed interface NanoDownloadProgress {
    /** [percent] is null when the total size isn't known yet. */
    data class Downloading(val percent: Int?) : NanoDownloadProgress

    data object Done : NanoDownloadProgress

    data class Failed(val message: String?) : NanoDownloadProgress
}

/**
 * Reports whether system Gemini Nano is usable and drives its on-demand
 * download (ML Kit GenAI `checkStatus()` / `download()`). The seam keeps the
 * AI module's tiering testable with a fake and lets non-ML-Kit callers stay
 * agnostic. The real impl is [MlKitNanoAvailability].
 */
interface NanoAvailability {
    suspend fun status(): NanoStatus

    /** Downloads the Nano feature; emits progress then a terminal state. */
    fun download(): Flow<NanoDownloadProgress>
}

/** Nano reported unavailable (no ML Kit / unsupported device). */
class StubNanoAvailability : NanoAvailability {
    override suspend fun status(): NanoStatus = NanoStatus.UNAVAILABLE

    override fun download(): Flow<NanoDownloadProgress> =
        flowOf(NanoDownloadProgress.Failed("Nano is not available on this device"))
}
