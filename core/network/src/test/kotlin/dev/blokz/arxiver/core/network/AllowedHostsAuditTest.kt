package dev.blokz.arxiver.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The egress red-line pin (CLAUDE.md "Network calls only to …"). This asserts the ALLOWED host set is EXACTLY
 * the sanctioned list — adding or removing a host is a deliberate red-line decision that must edit this test in
 * the same commit, so no feature can silently widen the network surface. P-Discover-MLT's discovery call rides
 * the ALREADY-present `api.semanticscholar.org`; this test is its checkpoint gate (the phase adds NO host).
 */
class AllowedHostsAuditTest {
    private val sanctioned =
        setOf(
            // arXiv group
            "export.arxiv.org",
            "arxiv.org",
            "ar5iv.labs.arxiv.org",
            // Semantic Scholar (citation graph + PT.3 search + P-Discover-MLT recommendations — SAME host)
            "api.semanticscholar.org",
            // chemRxiv
            "chemrxiv.org",
            // bioRxiv / medRxiv PDF hosts
            "www.biorxiv.org",
            "www.medrxiv.org",
            // OpenAlex aggregator
            "api.openalex.org",
            // bioRxiv/medRxiv native discovery API
            "api.biorxiv.org",
            // pinned model download
            "huggingface.co",
        )

    @Test
    fun `the allowlist is exactly the ten sanctioned hosts - no silent widening`() {
        assertEquals(sanctioned, AllowedHosts.ALLOWED)
        assertEquals(10, AllowedHosts.ALLOWED.size)
    }

    @Test
    fun `Semantic Scholar is present - the discovery + citation + search host`() {
        assertTrue("api.semanticscholar.org" in AllowedHosts.ALLOWED)
    }

    @Test
    fun `no BYOK AI provider host leaks into the egress-gated allowlist`() {
        // The AI provider hosts (Anthropic / Gemini) reach the network on their OWN bare clients with a
        // user-supplied key — they are deliberately NOT on the @ArxivClient gate. A leak here would route
        // gated traffic to them.
        assertTrue("api.anthropic.com" !in AllowedHosts.ALLOWED)
        assertTrue("generativelanguage.googleapis.com" !in AllowedHosts.ALLOWED)
    }
}
