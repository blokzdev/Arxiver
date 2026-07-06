package dev.blokz.arxiver.core.network.pdf

import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * The R6 red-line guard (SPEC-P-SOURCES §7). The load-bearing property is **reference identity**: for an
 * arXiv-group host [PdfHostPolicy] must return the EXACT [ArxivRateLimiter] singleton it was constructed
 * with (the one the Atom/HTML fetchers also hold), not merely a limiter that happens to be 3s-spaced —
 * else arXiv PDF requests stop FIFO-serializing against Atom/HTML and the global ≥3s spacing silently
 * breaks. This test proves the policy returns what it was CONSTRUCTED with; that the Hilt DI actually
 * passes the injected singleton (not a fresh `ArxivRateLimiter()`) is guarded separately by the `:app`
 * `PdfLimiterWiringTest` (a source-structural check on the `pdfDownloader` provider).
 */
class PdfHostPolicyTest {
    private val arxiv = ArxivRateLimiter(minSpacingMs = 3_000)
    private val polite = ArxivRateLimiter(minSpacingMs = 1_200)
    private val policy = PdfHostPolicy(arxivLimiter = arxiv, politeLimiter = polite)

    @Test
    fun `every arxiv-group host resolves to the arxiv singleton by reference`() {
        assertSame(arxiv, policy.limiterFor("https://arxiv.org/pdf/2403.01234v1"))
        assertSame(arxiv, policy.limiterFor("https://export.arxiv.org/api/query?id_list=2403.01234"))
        assertSame(arxiv, policy.limiterFor("https://ar5iv.labs.arxiv.org/html/2403.01234"))
    }

    @Test
    fun `a non-arxiv host resolves to the polite limiter, never the arxiv one`() {
        val chem = policy.limiterFor("https://chemrxiv.org/engage/api-gateway/chemrxiv/assets/x.pdf")
        assertSame(polite, chem)
        assertNotSame(arxiv, chem)
    }

    @Test
    fun `bioRxiv and medRxiv PDF hosts resolve to the polite limiter, never the arxiv singleton (PS2)`() {
        // Host-keyed: the two new allowlisted PDF hosts self-space on the ~1.2s polite slot and must NEVER
        // serialize on the arXiv ≥3s singleton (the R6 red line). PdfHostPolicy needs no change — it keys
        // on host, so both fall to `politeLimiter` automatically.
        val bio = policy.limiterFor("https://www.biorxiv.org/content/10.1101/2024.01.07.574543v1.full.pdf")
        assertSame(polite, bio)
        assertNotSame(arxiv, bio)
        val med = policy.limiterFor("https://www.medrxiv.org/content/10.1101/2024.02.02.24302001v1.full.pdf")
        assertSame(polite, med)
        assertNotSame(arxiv, med)
    }

    @Test
    fun `an off-host chemrxiv CDN sub-domain is polite, not arxiv`() {
        // A `pdfUrl` that 302s to a CDN sub-domain must NOT be mistaken for an arXiv host (it isn't one of
        // the three named arXiv hosts) — it self-spaces on the polite slot.
        assertSame(polite, policy.limiterFor("https://assets.chemrxiv.org/x.pdf"))
    }

    @Test
    fun `an unparseable url defaults to the arxiv limiter, never under-spacing the red line`() {
        assertSame(arxiv, policy.limiterFor("not a url"))
        assertSame(arxiv, policy.limiterFor(""))
    }

    @Test
    fun `the user agent is the app-identifying default for every host`() {
        assertEquals(ArxivApiClient.DEFAULT_USER_AGENT, policy.userAgentFor("https://arxiv.org/pdf/x"))
        assertEquals(ArxivApiClient.DEFAULT_USER_AGENT, policy.userAgentFor("https://chemrxiv.org/x.pdf"))
    }
}
