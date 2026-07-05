package dev.blokz.arxiver.core.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
