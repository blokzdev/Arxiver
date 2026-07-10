package dev.blokz.arxiver.core.network.openalex

import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.ExternalRef
import dev.blokz.arxiver.core.model.PaperSource
import dev.blokz.arxiver.core.model.Source
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The PE.3 search mapper — identity rules, census-honest degradation, and the reachability guard. The golden leg
 * reads the REAL OpenAlex `/works?search=` capture (`openalex/chemrxiv_search.json`), so a drifted field name
 * fails here, not on a phone.
 */
class OpenAlexPaperMapperTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun goldenWorks(): List<OpenAlexWork> =
        json.decodeFromString<OpenAlexResponse>(
            checkNotNull(javaClass.classLoader.getResource("openalex/chemrxiv_search.json")).readText(),
        ).results

    @Test
    fun `the golden chemRxiv work maps identity, metadata, and links`() {
        val paper = assertNotNull(goldenWorks().first().toPaper(Source.CHEMRXIV))

        assertEquals(ExternalRef(Source.CHEMRXIV, "10.26434/chemrxiv-2024-9lpb9"), paper.ref)
        assertEquals("10.26434/chemrxiv-2024-9lpb9", paper.doi, "the DOI keys verbatim")
        assertTrue(paper.title.startsWith("Digital Catalysis Platform"), paper.title)
        assertEquals("Materials Science", paper.primaryCategory, "OpenAlex Field label reaches the category")
        assertTrue(paper.abstract.isNotBlank(), "the inverted-index abstract is reconstructed")
        assertEquals("", paper.pdfUrl, "a null oa pdf degrades to blank, never a fake URL")
        assertEquals("https://doi.org/10.26434/chemrxiv-2024-9lpb9", paper.landingUrl)
        assertEquals(PaperSource.SEARCH, paper.source)
        assertEquals("2024-12-20T00:00:00Z", paper.publishedAt.toString())
    }

    private fun work(
        id: String? = "https://openalex.org/W777",
        doi: String? = null,
        landing: String? = null,
        pdf: String? = null,
        arxivLanding: String? = null,
    ) = OpenAlexWork(
        id = id,
        doi = doi,
        title = "t",
        publicationDate = "2026-01-01",
        primaryLocation =
            OpenAlexLocation(
                pdfUrl = pdf,
                landingPageUrl = landing,
                source = OpenAlexSource(id = "https://openalex.org/S4306401687"),
            ),
        locations =
            listOfNotNull(
                arxivLanding?.let {
                    OpenAlexLocation(
                        landingPageUrl = it,
                        source = OpenAlexSource(id = "https://openalex.org/${OpenAlexClient.SID_ARXIV}"),
                    )
                },
            ),
    )

    @Test
    fun `a versioned DOI keys verbatim — normalization is a lookup concern, not an identity rewrite`() {
        val paper =
            assertNotNull(
                work(doi = "https://doi.org/10.26434/chemrxiv.7234721.v5").toPaper(Source.CHEMRXIV),
            )
        assertEquals("chemrxiv:10.26434/chemrxiv.7234721.v5", paper.ref.storageId)
    }

    @Test
    fun `a DOI-less work keys under its OpenAlex id and the landing page is its only link`() {
        // 99.4% of recent PsyArXiv works look exactly like this (PE.1b).
        val paper = assertNotNull(work(landing = "https://osf.io/szf8y").toPaper(Source.PSYARXIV))
        assertEquals("psyarxiv:W777", paper.ref.storageId)
        assertNull(paper.doi, "no fake DOI is synthesized")
        assertEquals("https://osf.io/szf8y", paper.canonicalUrl())
    }

    @Test
    fun `an arXiv cross-post collapses to the bare arXiv ref and synthesizes its pdf url`() {
        val paper =
            assertNotNull(
                work(
                    doi = "https://doi.org/10.26434/chemrxiv-x",
                    arxivLanding = "https://arxiv.org/abs/2403.09999",
                ).toPaper(Source.CHEMRXIV),
            )
        assertTrue(paper.ref is ArxivRef, "the crosswalk keys under arXiv")
        assertEquals("2403.09999", paper.ref.storageId)
        assertTrue("arxiv.org" in paper.pdfUrl, "a null oa pdf falls back to the synthesized arXiv PDF")
    }

    @Test
    fun `a work with neither doi nor id maps to null`() {
        assertNull(work(id = null).toPaper(Source.PSYARXIV))
    }

    @Test
    fun `an unreachable work — no doi, no landing, no pdf — is dropped`() {
        // The census tail: nothing to open anywhere. A dead card helps nobody.
        assertNull(work(landing = null, pdf = null).toPaper(Source.PSYARXIV))
    }
}
