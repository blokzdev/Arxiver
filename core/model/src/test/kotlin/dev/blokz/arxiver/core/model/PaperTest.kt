package dev.blokz.arxiver.core.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaperTest {
    private fun paper(version: Int = 1) =
        Paper(
            ref = ArxivRef(ArxivId("2403.01234")),
            latestVersion = version,
            title = "t",
            abstract = "a",
            publishedAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            primaryCategory = "cs.LG",
            categories = listOf("cs.LG"),
            authors = listOf("A"),
        )

    @Test
    fun `pdfUrl defaults to the versioned arxiv url from id`() {
        assertEquals("https://arxiv.org/pdf/2403.01234v3", paper(version = 3).pdfUrl)
    }

    @Test
    fun `defaults are SEARCH source and no citation count`() {
        val p = paper()
        assertEquals(PaperSource.SEARCH, p.source)
        assertEquals(null, p.citationCount)
        assertEquals(null, p.doi)
    }

    @Test
    fun `explicit pdfUrl overrides the derived default`() {
        val p = paper().copy(pdfUrl = "https://example.org/x.pdf")
        assertEquals("https://example.org/x.pdf", p.pdfUrl)
    }

    @Test
    fun `canonicalUrl for an arxiv paper is the abstract page, unversioned`() {
        assertEquals("https://arxiv.org/abs/2403.01234", paper(version = 3).canonicalUrl())
    }

    @Test
    fun `canonicalUrl for a non-arxiv paper prefers the doi resolver`() {
        val p =
            Paper(
                ref = ExternalRef(Source.CHEMRXIV, "10.26434/chemrxiv-2024-xyz"),
                latestVersion = 1,
                title = "t",
                abstract = "a",
                publishedAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                primaryCategory = "",
                categories = emptyList(),
                authors = listOf("A"),
                doi = "10.26434/chemrxiv-2024-xyz",
                pdfUrl = "https://chemrxiv.org/engage/api-gateway/chemrxiv/assets/x.pdf",
            )
        assertEquals("https://doi.org/10.26434/chemrxiv-2024-xyz", p.canonicalUrl())
    }

    @Test
    fun `canonicalUrl for a non-arxiv paper with no doi falls back to the stored pdf url`() {
        val p =
            Paper(
                ref = ExternalRef(Source.CHEMRXIV, "native-1"),
                latestVersion = 1,
                title = "t",
                abstract = "a",
                publishedAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                primaryCategory = "",
                categories = emptyList(),
                authors = listOf("A"),
                doi = null,
                pdfUrl = "https://chemrxiv.org/x.pdf",
            )
        assertEquals("https://chemrxiv.org/x.pdf", p.canonicalUrl())
    }

    // --- P-Explorer PE.0: honest, source-aware pdf_fetchable (closes the P-Dispatch deferral) ---

    private fun external(
        source: Source,
        pdfUrl: String,
    ) = Paper(
        ref = ExternalRef(source, "native-1"),
        latestVersion = 1,
        title = "t",
        abstract = "a",
        publishedAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        primaryCategory = "",
        categories = emptyList(),
        authors = listOf("A"),
        pdfUrl = pdfUrl,
    )

    @Test
    fun `bioRxiv and medRxiv report their pdf as fetchable (they serve real PDF bytes on an allowlisted host)`() {
        // The old `ref is ArxivRef` rule reported these false — the bug this closes.
        assertTrue(external(Source.BIORXIV, "https://www.biorxiv.org/content/10.1101/xv1.full.pdf").isPdfFetchable())
        assertTrue(external(Source.MEDRXIV, "https://www.medrxiv.org/content/10.1101/yv1.full.pdf").isPdfFetchable())
    }

    @Test
    fun `gated and shared-CDN sources report their pdf as unfetchable`() {
        // chemRxiv: Atypon cookie-wall · SSRN: ToS-banned + Cloudflare · Preprints.org: edge-deny
        // PsyArXiv: only reachable via multi-tenant storage.googleapis.com · Research Square: pending device check
        listOf(Source.CHEMRXIV, Source.SSRN, Source.PREPRINTS_ORG, Source.PSYARXIV, Source.RESEARCH_SQUARE)
            .forEach {
                assertFalse(external(it, "https://example.org/x.pdf").isPdfFetchable(), "$it must be browser-only")
            }
    }

    @Test
    fun `an in-app source with no pdf url still reports unfetchable (never over-promises)`() {
        assertFalse(external(Source.BIORXIV, "").isPdfFetchable())
    }

    @Test
    fun `an arXiv paper reports its pdf as fetchable`() {
        assertTrue(paper().isPdfFetchable())
    }

    // --- P-Explorer PE.1b: a DOI-less, PDF-less source still has a reachable link ---

    @Test
    fun `canonicalUrl falls back to the landing page when a paper has neither doi nor pdf`() {
        // An OSF-hosted PsyArXiv paper. Before PE.1b this resolved to "" — literally no link at all.
        val p =
            Paper(
                ref = ExternalRef(Source.PSYARXIV, "W7112150394"),
                latestVersion = 1,
                title = "t",
                abstract = "a",
                publishedAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                primaryCategory = "Psychology",
                categories = emptyList(),
                authors = listOf("A"),
                doi = null,
                pdfUrl = "",
                landingUrl = "https://osf.io/szf8y",
            )
        assertEquals("https://osf.io/szf8y", p.canonicalUrl())
    }

    @Test
    fun `the doi resolver still outranks the landing page when both exist`() {
        val p =
            Paper(
                ref = ExternalRef(Source.CHEMRXIV, "10.26434/x"),
                latestVersion = 1,
                title = "t",
                abstract = "a",
                publishedAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                primaryCategory = "Chemistry",
                categories = emptyList(),
                authors = listOf("A"),
                doi = "10.26434/x",
                pdfUrl = "https://chemrxiv.org/x.pdf",
                landingUrl = "https://chemrxiv.org/engage/item/x",
            )
        assertEquals("https://doi.org/10.26434/x", p.canonicalUrl(), "the DOI stays the citeable canonical")
    }
}
