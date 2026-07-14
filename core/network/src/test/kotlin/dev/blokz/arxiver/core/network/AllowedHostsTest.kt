package dev.blokz.arxiver.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AllowedHostsTest {
    @Test
    fun `every allowed host resolves true`() {
        for (h in AllowedHosts.ALLOWED) assertTrue(AllowedHosts.isAllowed(h), h)
    }

    @Test
    fun `the allowlist is EXACTLY these 10 hosts — any add or removal is a red-line change`() {
        // A membership check (above) can't catch a NEW host being added. This exact-set pin (Phase P-Reader2
        // PFT.5.1) fails CI the moment the egress perimeter changes in either direction — adding a host is a
        // Co-Founder red-line decision (CLAUDE.md "Network calls only to …"), so editing this set must be a
        // deliberate, reviewed act, never an incidental import. If you're here because pdfbox tempted you to
        // add a host: it must not — it reads already-downloaded local files only.
        assertEquals(
            setOf(
                "export.arxiv.org",
                "arxiv.org",
                "ar5iv.labs.arxiv.org",
                "api.semanticscholar.org",
                "chemrxiv.org",
                "www.biorxiv.org",
                "www.medrxiv.org",
                "api.openalex.org",
                "api.biorxiv.org",
                "huggingface.co",
            ),
            AllowedHosts.ALLOWED,
        )
    }

    @Test
    fun `the host check is case-insensitive`() {
        assertTrue(AllowedHosts.isAllowed("API.SemanticScholar.org"))
        assertTrue(AllowedHosts.isAllowed("ArXiv.org"))
    }

    @Test
    fun `chemrxiv is allowlisted but its asset CDN sub-domains are not (PT4)`() {
        assertTrue(AllowedHosts.isAllowed("chemrxiv.org"))
        assertTrue(AllowedHosts.isAllowed("ChemRxiv.org"))
        // Exact-match: an off-host asset CDN sub-domain must NOT pass (the redirect-block red line).
        assertFalse(AllowedHosts.isAllowed("assets.chemrxiv.org"))
        assertFalse(AllowedHosts.isAllowed("chemrxiv.org.evil.example"))
        assertFalse(AllowedHosts.isAllowed("evilchemrxiv.org"))
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

    @Test
    fun `bioRxiv and medRxiv PDF hosts are allowlisted, their bare-domain and CDN variants are not (PS2)`() {
        assertTrue(AllowedHosts.isAllowed("www.biorxiv.org"))
        assertTrue(AllowedHosts.isAllowed("WWW.MedRxiv.org"))
        // Exact-match: the bare apex and any CDN/sub-domain variant fail closed.
        assertFalse(AllowedHosts.isAllowed("biorxiv.org"))
        assertFalse(AllowedHosts.isAllowed("medrxiv.org"))
        assertFalse(AllowedHosts.isAllowed("cdn.biorxiv.org"))
    }

    @Test
    fun `PF3 new-source PDF hosts are NOT allowlisted, so their reads degrade to external-open`() {
        // Research Square / SSRN / Preprints.org / OSF-PsyArXiv PDFs are host-gated read-only until a per-source
        // Co-Founder allowlist decision (deferred). oaPdfUrl on these hosts must fail closed at download.
        assertFalse(AllowedHosts.isAllowedUrl("https://www.researchsquare.com/article/rs-1/v1.pdf"))
        assertFalse(AllowedHosts.isAllowedUrl("https://papers.ssrn.com/sol3/papers.cfm?abstract_id=1.pdf"))
        assertFalse(AllowedHosts.isAllowedUrl("https://www.preprints.org/manuscript/1/v1/download"))
        assertFalse(AllowedHosts.isAllowedUrl("https://osf.io/preprints/psyarxiv/x/download"))
    }

    @Test
    fun `isAllowedUrl gates a full URL by its host, failing closed on doi and malformed`() {
        assertTrue(AllowedHosts.isAllowedUrl("https://www.biorxiv.org/content/10.1101/2024.01.07.574543v1.full.pdf"))
        assertTrue(AllowedHosts.isAllowedUrl("https://www.medrxiv.org/content/10.1101/2024.02.02.24302001v1.full.pdf"))
        assertTrue(AllowedHosts.isAllowedUrl("https://chemrxiv.org/engage/api-gateway/chemrxiv/assets/x.pdf"))
        // A DOI resolver or arbitrary publisher host is NOT allowlisted → read-only, never in-app.
        assertFalse(AllowedHosts.isAllowedUrl("https://doi.org/10.1101/2024.01.07.574543"))
        assertFalse(AllowedHosts.isAllowedUrl("https://www.nature.com/articles/x.pdf"))
        // An off-host CDN variant of an allowlisted domain fails closed.
        assertFalse(AllowedHosts.isAllowedUrl("https://assets.chemrxiv.org/x.pdf"))
        // Null / malformed / non-HTTP → null host → false.
        assertFalse(AllowedHosts.isAllowedUrl(null))
        assertFalse(AllowedHosts.isAllowedUrl(""))
        assertFalse(AllowedHosts.isAllowedUrl("not a url"))
        assertFalse(AllowedHosts.isAllowedUrl("ftp://www.biorxiv.org/x.pdf"))
        // http:// fails closed — aligned with the https-only fetch interceptor (an http OA URL would be
        // flagged importable but then rejected at download).
        assertFalse(AllowedHosts.isAllowedUrl("http://www.biorxiv.org/x.pdf"))
    }
}
