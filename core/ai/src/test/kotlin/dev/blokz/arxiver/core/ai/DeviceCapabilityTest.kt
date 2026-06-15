package dev.blokz.arxiver.core.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceCapabilityTest {
    private fun withRam(ramMb: Long) =
        DeviceCapability(
            totalRamMb = ramMb,
            nanoStatus = NanoStatus.UNAVAILABLE,
            gemmaReady = false,
            cloudConfigured = false,
        )

    @Test
    fun `gemma eligible at and above the ram floor`() {
        assertTrue(withRam(DeviceCapability.GEMMA_RAM_FLOOR_MB).gemmaEligible)
        assertTrue(withRam(8192).gemmaEligible)
    }

    @Test
    fun `gemma not eligible below the ram floor`() {
        assertFalse(withRam(DeviceCapability.GEMMA_RAM_FLOOR_MB - 1).gemmaEligible)
        assertFalse(withRam(2048).gemmaEligible)
    }
}
