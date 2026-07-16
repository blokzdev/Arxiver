package dev.blokz.arxiver.core.network.openalex

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The pure P-OA picker ([OaFulltextResolver.pick]). A miss must always beat a wrong hit, so most cases assert a
 * `null` (no button) when any guard fails. The motivating fixture is the live-verified Research-Square → Wiley
 * crosswalk.
 */
class OaFulltextResolverTest {
    private val ourTitle =
        "Experimental evidence of microbial inheritance in plants and transmission routes " +
            "from seed to phyllosphere and root"
    private val ourDoi = "10.21203/rs.3.rs-27656/v1"
    private val wileyPdf = "https://sfamjournals.onlinelibrary.wiley.com/doi/pdfdirect/10.1111/1462-2920.15392"

    private fun query(
        title: String = ourTitle,
        author: String? = "Ahmed Abdelfattah",
        doi: String? = ourDoi,
        canonicalHost: String? = "doi.org",
    ): OaFulltextResolver.Query {
        val norm = OaFulltextResolver.normalizeTitle(title)
        return OaFulltextResolver.Query(
            baseDoi = OaFulltextResolver.baseDoiKey(doi),
            openAlexId = null,
            normTitle = norm,
            titleWordCount = if (norm.isBlank()) 0 else norm.split(' ').count { it.isNotEmpty() },
            firstSurname = OaFulltextResolver.surnameOf(author),
            canonicalHost = canonicalHost,
        )
    }

    private fun source(name: String) = OpenAlexSource(displayName = name)

    private fun publishedArticle(
        id: String = "W1",
        doi: String = "10.1111/1462-2920.15392",
        title: String = ourTitle,
        author: String? = "Ahmed Abdelfattah",
        pdf: String? = wileyPdf,
        journal: String = "Environmental Microbiology",
        version: String = "publishedVersion",
        workIsOa: Boolean = true,
        locIsOa: Boolean? = true,
        citedBy: Int = 30,
        type: String = "article",
        retracted: Boolean? = null,
    ) = OpenAlexWork(
        id = "https://openalex.org/$id",
        doi = "https://doi.org/$doi",
        title = title,
        authorships = author?.let { listOf(OpenAlexAuthorship(OpenAlexAuthor(it))) } ?: emptyList(),
        primaryLocation = OpenAlexLocation(source = source(journal)),
        bestOaLocation = OpenAlexLocation(pdfUrl = pdf, version = version, isOa = locIsOa, source = source(journal)),
        openAccess = OpenAlexOpenAccess(isOa = workIsOa, oaStatus = "hybrid"),
        type = type,
        citedByCount = citedBy,
        isRetracted = retracted,
    )

    private fun preprintSelf() =
        OpenAlexWork(
            id = "https://openalex.org/W2",
            doi = "https://doi.org/$ourDoi",
            title = ourTitle,
            authorships = listOf(OpenAlexAuthorship(OpenAlexAuthor("Ahmed Abdelfattah"))),
            primaryLocation = OpenAlexLocation(source = source("Research Square")),
            bestOaLocation =
                OpenAlexLocation(
                    pdfUrl = "https://www.researchsquare.com/article/rs-27656/v1.pdf",
                    version = "acceptedVersion",
                    isOa = true,
                    source = source("Research Square"),
                ),
            openAccess = OpenAlexOpenAccess(isOa = true, oaStatus = "green"),
            type = "preprint",
            citedByCount = 10,
        )

    @Test
    fun `motivating case — returns the published Wiley PDF and rejects the preprint sibling and distractors`() {
        val distractor =
            publishedArticle(id = "W9", doi = "10.9/x", title = "A totally different study", author = "Chao Xiong")
        val match = OaFulltextResolver.pick(query(), listOf(publishedArticle(), preprintSelf(), distractor))
        assertEquals(wileyPdf, match?.pdfUrl)
        assertEquals("Environmental Microbiology", match?.journalName)
        assertEquals(true, match?.versionOfRecord)
    }

    @Test
    fun `author surname mismatch is rejected even with a near-identical title`() {
        val wrong = publishedArticle(author = "Jane Smith")
        assertNull(OaFulltextResolver.pick(query(), listOf(wrong)))
    }

    @Test
    fun `no-author query needs an exact, distinctive title — matches long, rejects a short generic one`() {
        // Author-stripped (SSRN / RS): first surname is null, so Tier B demands EXACT title + >= 5 words.
        val q = query(author = null)
        assertEquals(wileyPdf, OaFulltextResolver.pick(q, listOf(publishedArticle()))?.pdfUrl)

        // A short generic title (< 5 words) is never crosswalked without an author anchor.
        val shortTitle = "Editorial board"
        val shortQ = query(title = shortTitle, author = null, doi = "10.1/pre")
        val shortArticle = publishedArticle(id = "W5", doi = "10.2/pub", title = shortTitle)
        assertNull(OaFulltextResolver.pick(shortQ, listOf(shortArticle)))
    }

    @Test
    fun `no-author query rejects a fuzzy (non-exact) title match`() {
        val q = query(author = null)
        val reworded = publishedArticle(title = "$ourTitle in cereal crops")
        assertNull(OaFulltextResolver.pick(q, listOf(reworded)))
    }

    @Test
    fun `a sibling that is not the published version of record is rejected`() {
        val accepted = publishedArticle(version = "acceptedVersion")
        assertNull(OaFulltextResolver.pick(query(), listOf(accepted)))
    }

    @Test
    fun `open-access and pdf gates — a paywalled or pdf-less sibling is rejected`() {
        assertNull(OaFulltextResolver.pick(query(), listOf(publishedArticle(workIsOa = false))), "work not is_oa")
        assertNull(OaFulltextResolver.pick(query(), listOf(publishedArticle(locIsOa = false))), "location not is_oa")
        assertNull(OaFulltextResolver.pick(query(), listOf(publishedArticle(pdf = null))), "no pdf url")
    }

    @Test
    fun `a retracted published sibling is never surfaced`() {
        assertNull(OaFulltextResolver.pick(query(), listOf(publishedArticle(retracted = true))))
    }

    @Test
    fun `an erratum-typed or correction-titled work is rejected`() {
        assertNull(OaFulltextResolver.pick(query(), listOf(publishedArticle(type = "erratum"))), "erratum type")
        val correction = publishedArticle(id = "W7", doi = "10.3/corr", title = "Author Correction: $ourTitle")
        assertNull(OaFulltextResolver.pick(query(), listOf(correction)), "correction-prefixed title")
    }

    @Test
    fun `Tier A — a merged version-of-record on our own DOI is returned without needing author or title match`() {
        // Our stored DOI already resolves to the published record (OpenAlex merged the versions). No author on the
        // query at all — DOI identity is exact, so there is no collision risk.
        val merged = publishedArticle(id = "W1", doi = ourDoi, author = null)
        val q = query(author = null, title = "unrelated words that will not match by title")
        val match = OaFulltextResolver.pick(q, listOf(merged))
        assertEquals(wileyPdf, match?.pdfUrl)
        assertEquals(true, match?.versionOfRecord)
    }

    @Test
    fun `Tier C — the paper's own OA PDF is surfaced as free (not published) and echo-suppressed by host`() {
        val selfOa =
            OpenAlexWork(
                id = "https://openalex.org/W2",
                doi = "https://doi.org/$ourDoi",
                title = ourTitle,
                authorships = listOf(OpenAlexAuthorship(OpenAlexAuthor("Ahmed Abdelfattah"))),
                bestOaLocation =
                    OpenAlexLocation(pdfUrl = "https://chemrxiv.org/x.pdf", version = "submittedVersion", isOa = true),
                openAccess = OpenAlexOpenAccess(isOa = true),
                type = "preprint",
            )
        // Different host than canonicalUrl → surfaced as a free (non-VoR) PDF.
        val found = OaFulltextResolver.pick(query(canonicalHost = "doi.org"), listOf(selfOa))
        assertEquals("https://chemrxiv.org/x.pdf", found?.pdfUrl)
        assertFalse(found!!.versionOfRecord)
        assertNull(found.journalName)

        // Same host as the existing "Open in browser" affordance → suppressed (would just duplicate it).
        assertNull(OaFulltextResolver.pick(query(canonicalHost = "chemrxiv.org"), listOf(selfOa)))
    }

    @Test
    fun `tie-break prefers the higher citation count among qualifying published siblings`() {
        val low = publishedArticle(id = "W1", doi = "10.1/a", pdf = "https://a.org/low.pdf", citedBy = 5)
        val high = publishedArticle(id = "W2", doi = "10.2/b", pdf = "https://b.org/high.pdf", citedBy = 500)
        assertEquals("https://b.org/high.pdf", OaFulltextResolver.pick(query(), listOf(low, high))?.pdfUrl)
    }

    @Test
    fun `title normalization folds case, punctuation, and diacritics`() {
        val q = query(title = "Rôle of Z-factors: a STUDY!", author = "José Ñuñez")
        val article =
            publishedArticle(title = "Role of Z factors a study", author = "Jose Nunez", doi = "10.5/pub")
        // Different DOI (a real crosswalk), matched purely on normalized title + folded surname.
        assertEquals(wileyPdf, OaFulltextResolver.pick(q, listOf(article))?.pdfUrl)
    }

    @Test
    fun `baseDoiKey strips both a dot-vN and a slash-vN preprint version tail`() {
        assertEquals("10.21203/rs.3.rs-27656", OaFulltextResolver.baseDoiKey("10.21203/rs.3.rs-27656/v1"))
        assertEquals("10.26434/chemrxiv.7234721", OaFulltextResolver.baseDoiKey("10.26434/chemrxiv.7234721.v5"))
        // A legitimate DOI merely ending in a dotted number is untouched.
        assertEquals(
            "10.1101/2024.01.02.680000",
            OaFulltextResolver.baseDoiKey("https://doi.org/10.1101/2024.01.02.680000"),
        )
    }

    @Test
    fun `only the first N results are scanned`() {
        val filler =
            (1..OaFulltextResolver.DEFAULT_CAP).map {
                publishedArticle(id = "Wf$it", doi = "10.0/$it", title = "noise $it", author = "Nobody")
            }
        // The real sibling sits just past the cap → not found.
        val beyondCap = filler + publishedArticle()
        assertNull(OaFulltextResolver.pick(query(), beyondCap))
    }
}
