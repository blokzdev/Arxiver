package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.ml.ModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The light tier (Qwen3-0.6B) engine + its pinned model (P-Atlas PA.3). The SPEC pin guards against
 * the `.mediatek` NPU-only sibling (the F2-trap analog: it loads but emits zero tokens on CPU), and
 * the tier/richness guard that the light tier is LIGHT + PLAIN (never STRUCTURED — a 0.6B model isn't
 * pushed toward tables).
 */
class QwenEngineTest {
    private val dispatchers =
        object : DispatcherProvider {
            override val io = Dispatchers.Unconfined
            override val default = Dispatchers.Unconfined
            override val main = Dispatchers.Unconfined
        }

    private fun engine(): QwenEngine {
        val dir = Files.createTempDirectory("qwen-test").toFile()
        val downloader = ModelDownloader(OkHttpClient(), dispatchers, dir, QwenEngine.SPEC)
        return QwenEngine(downloader, dispatchers, dir)
    }

    @Test
    fun `SPEC pins the CPU litertlm build, not the NPU mediatek sibling`() {
        val spec = QwenEngine.SPEC
        assertEquals("Qwen3-0.6B.litertlm", spec.fileName)
        assertFalse(spec.fileName.contains("mediatek"), "must not regress to the NPU-only build")
        assertFalse(spec.fileName.contains("-web"), "must not be a WebGPU build")
        assertTrue(spec.url.endsWith(spec.fileName), "url must resolve the pinned file")
        assertEquals(
            "555579ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4",
            spec.sha256,
            "pinned to the verified CPU-build digest",
        )
        assertEquals(64, spec.sha256.length)
    }

    @Test
    fun `the light engine is the LIGHT tier and PLAIN richness`() {
        val engine = engine()
        assertEquals(InferenceTier.LIGHT, engine.tier)
        // PLAIN, not STRUCTURED: a 0.6B model is prose-first and never nudged toward tables (PA.0).
        assertEquals(OutputRichness.PLAIN, engine.richness)
    }

    @Test
    fun `not ready until the model file is present`() =
        runBlocking {
            assertFalse(engine().isReady(), "no model file in a fresh temp dir")
        }
}
