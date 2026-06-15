package dev.blokz.arxiver.core.ai

/** System Gemini Nano availability — mirrors ML Kit GenAI's `FeatureStatus`. */
enum class NanoStatus { UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

/**
 * Snapshot of what on-device/edge inference the current device can do
 * (SPEC-AI-PROVIDERS §3). Pure data — produced by a [DeviceCapabilityProbe] and
 * consumed by [TierSelector]; no Android types here so the tiering logic stays
 * unit-testable.
 */
data class DeviceCapability(
    val totalRamMb: Long,
    val nanoStatus: NanoStatus,
    val gemmaReady: Boolean,
    val cloudConfigured: Boolean,
) {
    /** Enough RAM to run the downloaded Gemma model (independent of whether it's installed). */
    val gemmaEligible: Boolean get() = totalRamMb >= GEMMA_RAM_FLOOR_MB

    companion object {
        /**
         * RAM floor for offering Gemma 4 E2B. Its Android runtime footprint is
         * ~1.4–1.7 GB; require headroom so the app and OS aren't starved.
         */
        const val GEMMA_RAM_FLOOR_MB = 4096L
    }
}
