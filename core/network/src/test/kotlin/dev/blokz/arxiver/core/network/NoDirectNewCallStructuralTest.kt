package dev.blokz.arxiver.core.network

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Structural guard (SPEC-P-HTML §7/§14): every fetch site in `:core:network` that calls
 * `httpClient.newCall(...)` must also claim a rate-limit slot — so the ≥3s arXiv red line can't be
 * silently re-bypassed by a future fetcher (the bug PH.2 fixed in `PdfDownloader`). A slot is claimed
 * either directly (`rateLimiter.acquire()`) or, since P-Sources PS.1's per-host PDF policy, via
 * `hostPolicy.limiterFor(url).acquire()` (matched by the `.limiterFor(` marker — [PdfHostPolicy] always
 * `.acquire()`s the returned limiter at that call site). The exceptions are the self-spacing search
 * clients `SemanticScholarClient` (`/s2/`, PT.3) and `ChemRxivClient` (`/chemrxiv/`, PT.4), each with its
 * own 1.2s politeness mutex — host-gated on the `@ArxivClient` allowlist but deliberately NOT ≥3s-throttled
 * by the shared arXiv limiter.
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
                    // A slot is claimed directly (`rateLimiter.acquire(`) or via the PS.1 per-host policy
                    // (`hostPolicy.limiterFor(url).acquire()`, matched by `.limiterFor(`).
                    val hasAcquire = text.contains("rateLimiter.acquire(") || text.contains(".limiterFor(")
                    // S2 (PT.3) + chemRxiv (PT.4) self-space via their own 1.2s politeness mutex, not the
                    // ≥3s ArxivRateLimiter — documented exceptions, gated on the @ArxivClient host allowlist.
                    val path = f.path.replace('\\', '/')
                    val isSelfSpacedException = path.contains("/s2/") || path.contains("/chemrxiv/")
                    !hasAcquire && !isSelfSpacedException
                }
                .map { it.name }
                .toList()

        assertTrue(
            offenders.isEmpty(),
            "newCall() without a rateLimiter.acquire() (and not the S2 mutex exception): $offenders",
        )
    }
}
