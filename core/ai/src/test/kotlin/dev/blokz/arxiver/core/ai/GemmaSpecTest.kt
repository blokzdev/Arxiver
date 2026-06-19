package dev.blokz.arxiver.core.ai

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the pinned on-device model. The WebGPU-only `-web` build has no CPU `TF_LITE_PREFILL_DECODE`
 * graph, so it generates zero tokens on CPU-backend devices (e.g. Galaxy S20) — F2 swapped it for the
 * standard CPU/GPU build. This test fails loudly if the SPEC ever regresses to a `-web` variant.
 */
class GemmaSpecTest {
    @Test
    fun `SPEC pins the standard CPU-capable litertlm build, not the WebGPU one`() {
        val spec = GemmaEngine.SPEC
        assertEquals("gemma-4-E2B-it.litertlm", spec.fileName)
        assertFalse(spec.fileName.contains("-web"), "must not regress to the WebGPU-only build")
        assertTrue(spec.url.endsWith(spec.fileName), "url must resolve the pinned file")
        assertEquals(64, spec.sha256.length, "sha256 must be a full 64-hex digest")
    }
}
