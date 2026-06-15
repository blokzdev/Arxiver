package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.ml.ModelDownloader
import dev.blokz.arxiver.core.ml.ModelState
import dev.blokz.arxiver.sync.SyncScheduler
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow seam over the Gemma model's download lifecycle so the AI-provider
 * settings ViewModel depends on this (not the downloader + scheduler directly)
 * and tests can drive it with an in-memory fake — same pattern as
 * `AiKeyStore`/`AiProviderStore`.
 */
interface OnDeviceModelController {
    val state: StateFlow<ModelState>

    /** Enqueue the download (unmetered-network constrained worker). */
    fun download()

    fun delete()
}

class DefaultOnDeviceModelController(
    private val downloader: ModelDownloader,
    private val syncScheduler: SyncScheduler,
) : OnDeviceModelController {
    override val state: StateFlow<ModelState> = downloader.state

    override fun download() = syncScheduler.downloadOnDeviceModel()

    override fun delete() = downloader.delete()
}
