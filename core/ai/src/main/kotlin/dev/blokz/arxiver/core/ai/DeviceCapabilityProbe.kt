package dev.blokz.arxiver.core.ai

/**
 * Reads the current device's inference [DeviceCapability] (total RAM, Nano
 * status, whether the Gemma model is installed, whether a cloud key is set).
 *
 * The Android implementation lives in the app layer — it needs `ActivityManager`,
 * the ML Kit availability check, and the model downloader — so this seam keeps
 * the `:core:ai` tiering logic dependency-free and testable with a fake.
 */
interface DeviceCapabilityProbe {
    suspend fun probe(): DeviceCapability
}
