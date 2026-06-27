package dev.blokz.arxiver.core.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AllowedHostsTest {
    @Test
    fun `every allowed host resolves true`() {
        for (h in AllowedHosts.ALLOWED) assertTrue(AllowedHosts.isAllowed(h), h)
    }

    @Test
    fun `the host check is case-insensitive`() {
        assertTrue(AllowedHosts.isAllowed("API.SemanticScholar.org"))
        assertTrue(AllowedHosts.isAllowed("ArXiv.org"))
    }

    @Test
    fun `null and unknown hosts are rejected`() {
        assertFalse(AllowedHosts.isAllowed(null))
        assertFalse(AllowedHosts.isAllowed(""))
        assertFalse(AllowedHosts.isAllowed("evil.example"))
        // sub-domain tricks must not pass (exact-match set, not suffix)
        assertFalse(AllowedHosts.isAllowed("arxiv.org.evil.example"))
        assertFalse(AllowedHosts.isAllowed("evilarxiv.org"))
    }

    @Test
    fun `CDN strip-list hosts are never allowlisted`() {
        // The reader strips these (SPEC-P-HTML sec 6); they must not be reachable.
        assertFalse(AllowedHosts.isAllowed("use.typekit.net"))
        assertFalse(AllowedHosts.isAllowed("cdn.jsdelivr.net"))
    }
}
