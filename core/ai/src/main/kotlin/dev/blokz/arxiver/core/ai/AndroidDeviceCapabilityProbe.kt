package dev.blokz.arxiver.core.ai

import android.app.ActivityManager
import android.content.Context
import dev.blokz.arxiver.core.ml.ModelDownloader

/**
 * Reads the live [DeviceCapability] (SPEC-AI-PROVIDERS §3): total RAM from
 * `ActivityManager`, Nano status from [NanoAvailability], whether the Gemma
 * and Qwen-light model files are present, and whether any cloud key is set.
 * Display/recommendation reader only — resolution readiness is [OnDeviceProvider.isReady].
 */
class AndroidDeviceCapabilityProbe(
    private val context: Context,
    private val nanoAvailability: NanoAvailability,
    private val gemmaDownloader: ModelDownloader,
    private val lightDownloader: ModelDownloader,
    private val keyStore: AiKeyStore,
) : DeviceCapabilityProbe {
    override suspend fun probe(): DeviceCapability {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        return DeviceCapability(
            totalRamMb = memory.totalMem / (1024 * 1024),
            nanoStatus = nanoAvailability.status(),
            gemmaReady = gemmaDownloader.modelFile.exists(),
            lightReady = lightDownloader.modelFile.exists(),
            cloudConfigured = keyStore.has(ProviderId.CLAUDE) || keyStore.has(ProviderId.GEMINI),
        )
    }
}
