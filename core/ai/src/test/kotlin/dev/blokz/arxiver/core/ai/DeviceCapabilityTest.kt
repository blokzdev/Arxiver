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
            lightReady = false,
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

    // --- P-Atlas PA.3: the light tier has a lower floor than Gemma ---

    @Test
    fun `light tier eligible at and above its lower floor`() {
        assertTrue(withRam(DeviceCapability.LIGHT_RAM_FLOOR_MB).lightEligible)
        assertTrue(withRam(4096).lightEligible)
    }

    @Test
    fun `light tier not eligible below its floor`() {
        assertFalse(withRam(DeviceCapability.LIGHT_RAM_FLOOR_MB - 1).lightEligible)
        assertFalse(withRam(2048).lightEligible)
    }

    @Test
    fun `the light floor is lower than the gemma floor — it reaches devices gemma excludes`() {
        assertTrue(DeviceCapability.LIGHT_RAM_FLOOR_MB < DeviceCapability.GEMMA_RAM_FLOOR_MB)
        // A 3-4 GB device: light-eligible but not gemma-eligible (the tier's whole purpose).
        val midRange = withRam(3584)
        assertTrue(midRange.lightEligible)
        assertFalse(midRange.gemmaEligible)
    }
}
