package dev.blokz.arxiver.core.ai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Sibling of `:core:network`'s NoDirectNewCallStructuralTest — the existing one walks only the
 * `:core:network` tree, so a `:core:ai` **arXiv** fetcher would escape it. Guards that any
 * `httpClient.newCall(...)` in `:core:ai` **arXiv-fetch** code is paired with a `rateLimiter.acquire()`
 * (the ≥3s arXiv red line). The AI-provider transports (`AnthropicProvider`/`GeminiProvider`) are the
 * documented exception: they call their own non-arXiv APIs (api.anthropic.com / Google) and the arXiv
 * limiter doesn't apply to them — exactly parallel to the S2 mutex exception in `:core:network`. The
 * per-attempt acquire-COUNT assertions in `HtmlFetcherTest` are the load-bearing per-site guard.
 */
class HtmlFetcherNoBypassStructuralTest {
    @Test
    fun `every arxiv-fetch newCall in core-ai claims a rate-limit slot (AI providers excepted)`() {
        val root = File("src/main/kotlin/dev/blokz/arxiver/core/ai")
        assertTrue(root.isDirectory, "must run from the :core:ai module dir; cwd=${File("").absolutePath}")

        val offenders =
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { it.name.endsWith("Provider.kt") } // AI transports hit their own (non-arXiv) endpoints
                .filter { it.readText().contains(".newCall(") }
                .filter { !it.readText().contains("rateLimiter.acquire(") }
                .map { it.name }
                .toList()

        assertTrue(offenders.isEmpty(), "arXiv-fetch newCall() without a rateLimiter.acquire(): $offenders")
    }
}
