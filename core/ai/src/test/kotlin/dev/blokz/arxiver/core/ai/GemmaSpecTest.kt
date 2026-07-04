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

    // --- PA readiness hotfix (2026-07-03): pin isReady on BOTH sides (Gemma had zero coverage) ---

    private val dispatchers =
        object : dev.blokz.arxiver.core.common.DispatcherProvider {
            override val io = kotlinx.coroutines.Dispatchers.Unconfined
            override val default = kotlinx.coroutines.Dispatchers.Unconfined
            override val main = kotlinx.coroutines.Dispatchers.Unconfined
        }

    private fun engine(dir: java.io.File): GemmaEngine {
        val downloader =
            dev.blokz.arxiver.core.ml.ModelDownloader(
                okhttp3.OkHttpClient(),
                dispatchers,
                dir,
                GemmaEngine.SPEC,
            )
        return GemmaEngine(downloader, dispatchers, dir)
    }

    @Test
    fun `not ready with an empty model dir`() =
        kotlinx.coroutines.runBlocking {
            val dir = java.nio.file.Files.createTempDirectory("gemma-test").toFile()
            assertFalse(engine(dir).isReady())
        }

    @Test
    fun `ready once the pinned model file exists — presence-only, by contract`() =
        kotlinx.coroutines.runBlocking {
            // Same contract as QwenEngine: SHA enforced at download time; presence flips readiness.
            val dir = java.nio.file.Files.createTempDirectory("gemma-test").toFile()
            dir.resolve(GemmaEngine.SPEC.fileName).writeBytes(byteArrayOf(1))
            assertTrue(engine(dir).isReady())
        }
}
