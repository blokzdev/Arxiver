package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.ml.ModelDownloader
import dev.blokz.arxiver.core.ml.ModelState
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow seam over an on-device model's download lifecycle so the AI-provider
 * settings ViewModel depends on this (not the downloader + scheduler directly)
 * and tests can drive it with an in-memory fake — same pattern as
 * `AiKeyStore`/`AiProviderStore`. One instance per downloadable model (Gemma /
 * the light Qwen tier; P-Atlas PA.3b).
 */
interface OnDeviceModelController {
    val state: StateFlow<ModelState>

    /** Enqueue the download (unmetered-network constrained worker). */
    fun download()

    fun delete()
}

/**
 * @param enqueueDownload enqueues this model's download worker (e.g.
 * `SyncScheduler::downloadOnDeviceModel` for Gemma, `::downloadLightModel` for the light tier) —
 * a lambda so one class serves every model without a per-model subclass.
 */
class DefaultOnDeviceModelController(
    private val downloader: ModelDownloader,
    private val enqueueDownload: () -> Unit,
) : OnDeviceModelController {
    override val state: StateFlow<ModelState> = downloader.state

    override fun download() = enqueueDownload()

    override fun delete() = downloader.delete()
}
