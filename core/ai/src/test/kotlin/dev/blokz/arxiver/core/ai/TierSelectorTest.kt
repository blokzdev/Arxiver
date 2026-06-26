package dev.blokz.arxiver.core.ai

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tiering recommendation + degradation order (SPEC-AI-PROVIDERS §3). */
class TierSelectorTest {
    private fun cap(
        ramMb: Long = 8192,
        nano: NanoStatus = NanoStatus.UNAVAILABLE,
        gemmaReady: Boolean = false,
        lightReady: Boolean = false,
        cloud: Boolean = false,
    ) = DeviceCapability(
        totalRamMb = ramMb,
        nanoStatus = nano,
        gemmaReady = gemmaReady,
        lightReady = lightReady,
        cloudConfigured = cloud,
    )

    @Test
    fun `gemma wins over nano when both are ready`() {
        val all = cap(nano = NanoStatus.AVAILABLE, gemmaReady = true, cloud = true)
        assertEquals(InferenceTier.GEMMA, TierSelector.recommend(all))
    }

    @Test
    fun `nano recommended when gemma is not installed`() {
        assertEquals(
            InferenceTier.NANO,
            TierSelector.recommend(cap(nano = NanoStatus.AVAILABLE, gemmaReady = false, cloud = true)),
        )
    }

    @Test
    fun `gemma preferred over cloud when nano absent`() {
        assertEquals(
            InferenceTier.GEMMA,
            TierSelector.recommend(cap(nano = NanoStatus.DOWNLOADABLE, gemmaReady = true, cloud = true)),
        )
    }

    @Test
    fun `cloud used when only a key is set`() {
        assertEquals(InferenceTier.CLOUD, TierSelector.recommend(cap(cloud = true)))
    }

    @Test
    fun `none when nothing is usable`() {
        assertEquals(InferenceTier.NONE, TierSelector.recommend(cap()))
    }

    @Test
    fun `downloading nano does not count as available`() {
        assertEquals(
            InferenceTier.CLOUD,
            TierSelector.recommend(cap(nano = NanoStatus.DOWNLOADING, cloud = true)),
        )
    }

    @Test
    fun `fallback order is best-first and always ends in none`() {
        assertEquals(
            listOf(InferenceTier.GEMMA, InferenceTier.NANO, InferenceTier.CLOUD, InferenceTier.NONE),
            TierSelector.fallbackOrder(cap(nano = NanoStatus.AVAILABLE, gemmaReady = true, cloud = true)),
        )
        assertEquals(
            listOf(InferenceTier.GEMMA, InferenceTier.CLOUD, InferenceTier.NONE),
            TierSelector.fallbackOrder(cap(gemmaReady = true, cloud = true)),
        )
        assertEquals(listOf(InferenceTier.NONE), TierSelector.fallbackOrder(cap()))
    }

    // --- P-Atlas PA.3: the light (Qwen) tier ranks below Gemma, above Nano ---

    @Test
    fun `gemma wins over the light tier when both are ready`() {
        assertEquals(InferenceTier.GEMMA, TierSelector.recommend(cap(gemmaReady = true, lightReady = true)))
    }

    @Test
    fun `light tier recommended when gemma is not installed`() {
        assertEquals(InferenceTier.LIGHT, TierSelector.recommend(cap(lightReady = true, cloud = true)))
    }

    @Test
    fun `light tier beats nano (no 256-token cap)`() {
        assertEquals(
            InferenceTier.LIGHT,
            TierSelector.recommend(cap(nano = NanoStatus.AVAILABLE, lightReady = true)),
        )
    }

    @Test
    fun `full fallback order is gemma then light then nano then cloud then none`() {
        assertEquals(
            listOf(
                InferenceTier.GEMMA,
                InferenceTier.LIGHT,
                InferenceTier.NANO,
                InferenceTier.CLOUD,
                InferenceTier.NONE,
            ),
            TierSelector.fallbackOrder(
                cap(nano = NanoStatus.AVAILABLE, gemmaReady = true, lightReady = true, cloud = true),
            ),
        )
    }

    @Test
    fun `recommends from a probed capability`() =
        runBlocking {
            val probe =
                object : DeviceCapabilityProbe {
                    override suspend fun probe() = cap(gemmaReady = true)
                }
            assertEquals(InferenceTier.GEMMA, TierSelector.recommend(probe.probe()))
        }
}
