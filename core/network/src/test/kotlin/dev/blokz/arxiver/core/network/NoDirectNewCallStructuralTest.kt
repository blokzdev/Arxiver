package dev.blokz.arxiver.core.network

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Structural guard (SPEC-P-HTML §7/§14): every fetch site in `:core:network` that calls
 * `httpClient.newCall(...)` must also claim a `rateLimiter.acquire()` slot — so the ≥3s arXiv red line
 * can't be silently re-bypassed by a future fetcher (the bug this phase fixed in `PdfDownloader`).
 * The lone exception is `SemanticScholarClient`, which self-spaces with its own 1.2s mutex (documented
 * sole exception, on the bare client).
 */
class NoDirectNewCallStructuralTest {
    @Test
    fun `every newCall fetch site claims a rate-limit slot (S2 mutex excepted)`() {
        val root = File("src/main/kotlin/dev/blokz/arxiver/core/network")
        assertTrue(root.isDirectory, "must run from the :core:network module dir; cwd=${File("").absolutePath}")

        val offenders =
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.readText().contains(".newCall(") }
                .filter { f ->
                    val text = f.readText()
                    val hasAcquire = text.contains("rateLimiter.acquire(")
                    val isS2Exception = f.path.replace('\\', '/').contains("/s2/")
                    !hasAcquire && !isS2Exception
                }
                .map { it.name }
                .toList()

        assertTrue(
            offenders.isEmpty(),
            "newCall() without a rateLimiter.acquire() (and not the S2 mutex exception): $offenders",
        )
    }
}
