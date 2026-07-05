package dev.blokz.arxiver.core.model

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CitationTest {
    private val published = ZonedDateTime.of(2017, 6, 12, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()

    private fun paper(
        id: String = "1706.03762",
        title: String = "Attention Is All You Need",
        authors: List<String> = listOf("Ashish Vaswani", "Noam Shazeer", "Niki Parmar"),
        primaryCategory: String = "cs.CL",
        doi: String? = null,
        journalRef: String? = null,
        publishedAt: Instant = published,
    ) = Paper(
        ref = ArxivRef(ArxivId(id)),
        latestVersion = 1,
        title = title,
        abstract = "a",
        publishedAt = publishedAt,
        updatedAt = publishedAt,
        primaryCategory = primaryCategory,
        categories = listOf(primaryCategory),
        authors = authors,
        doi = doi,
        journalRef = journalRef,
    )

    @Test
    fun `bibtex emits an arxiv misc entry with the core fields`() {
        val bib = Citation.bibtex(paper())
        assertTrue(bib.startsWith("@misc{vaswani20173762,"), bib)
        assertContains(bib, "title = {Attention Is All You Need},")
        assertContains(bib, "author = {Ashish Vaswani and Noam Shazeer and Niki Parmar},")
        assertContains(bib, "year = {2017},")
        assertContains(bib, "eprint = {1706.03762},")
        assertContains(bib, "archivePrefix = {arXiv},")
        assertContains(bib, "primaryClass = {cs.CL},")
        assertContains(bib, "url = {https://arxiv.org/abs/1706.03762}")
        assertTrue(bib.trimEnd().endsWith("}"), bib)
    }

    @Test
    fun `bibtex omits doi and journal when absent and includes them when present`() {
        val without = Citation.bibtex(paper())
        assertFalse(without.contains("doi ="), without)
        assertFalse(without.contains("journal ="), without)

        val with = Citation.bibtex(paper(doi = "10.1000/xyz", journalRef = "NeurIPS 2017"))
        assertContains(with, "doi = {10.1000/xyz},")
        assertContains(with, "journal = {NeurIPS 2017},")
    }

    @Test
    fun `bibtex escapes braces in free-text fields so the entry parses`() {
        val bib = Citation.bibtex(paper(title = "A {Curly} Title"))
        assertContains(bib, "title = {A \\{Curly\\} Title},")
    }

    @Test
    fun `bibtex key uses surname, publication year and last id digits`() {
        // Legacy id, single-name author, year from publishedAt.
        val bib = Citation.bibtex(paper(id = "math/0211159", title = "Perelman", authors = listOf("Grisha Perelman")))
        assertTrue(bib.startsWith("@misc{perelman20171159,"), bib)
    }

    @Test
    fun `reference renders authors, year, title, arxiv id and abstract link`() {
        assertEquals(
            "Ashish Vaswani, Noam Shazeer, Niki Parmar (2017). Attention Is All You Need. " +
                "arXiv:1706.03762 [cs.CL]. https://arxiv.org/abs/1706.03762",
            Citation.reference(paper()),
        )
    }

    @Test
    fun `reference collapses to et al past three authors`() {
        val ref = Citation.reference(paper(authors = listOf("A One", "B Two", "C Three", "D Four")))
        assertContains(ref, "A One et al. (2017).")
        assertFalse(ref.contains("B Two"), ref)
    }

    @Test
    fun `reference prefers the doi link when published`() {
        val ref = Citation.reference(paper(doi = "10.1000/xyz"))
        assertTrue(ref.endsWith("https://doi.org/10.1000/xyz"), ref)
    }

    @Test
    fun `reference trims a trailing period from the title to avoid a double dot`() {
        val ref = Citation.reference(paper(title = "Ends With A Dot."))
        assertContains(ref, "Ends With A Dot. arXiv:")
        assertFalse(ref.contains("Dot.. arXiv"), ref)
    }

    // --- non-arXiv (chemRxiv, PS.1): no fake arXiv fields; DOI + brand label instead ---

    private fun chemPaper(
        doi: String = "10.26434/chemrxiv-2024-xyz",
        title: String = "A Chemistry Preprint",
        authors: List<String> = listOf("Marie Curie"),
    ) = Paper(
        ref = ExternalRef(Source.CHEMRXIV, doi),
        latestVersion = 1,
        title = title,
        abstract = "a",
        publishedAt = published,
        updatedAt = published,
        primaryCategory = "",
        categories = emptyList(),
        authors = authors,
        doi = doi,
        pdfUrl = "https://chemrxiv.org/x.pdf",
    )

    @Test
    fun `bibtex for a chemRxiv paper carries no arXiv eprint or archivePrefix`() {
        val bib = Citation.bibtex(chemPaper())
        assertFalse(bib.contains("archivePrefix = {arXiv}"), bib)
        assertFalse(bib.contains("eprint = {"), bib)
        assertFalse(bib.contains("primaryClass"), bib)
        assertContains(bib, "howpublished = {chemRxiv},")
        assertContains(bib, "doi = {10.26434/chemrxiv-2024-xyz},")
        assertContains(bib, "url = {https://doi.org/10.26434/chemrxiv-2024-xyz}")
    }

    @Test
    fun `reference for a chemRxiv paper shows the brand label and doi link, no arXiv segment`() {
        assertEquals(
            "Marie Curie (2017). A Chemistry Preprint. chemRxiv. https://doi.org/10.26434/chemrxiv-2024-xyz",
            Citation.reference(chemPaper()),
        )
    }
}
