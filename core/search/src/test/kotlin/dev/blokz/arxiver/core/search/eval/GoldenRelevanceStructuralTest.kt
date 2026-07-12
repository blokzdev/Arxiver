package dev.blokz.arxiver.core.search.eval

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural CI gate on the golden relevance fixture (`core/search/src/test/resources/golden_relevance.json`,
 * P-Prove PP.5) — the query→expected-paper set the on-device C3 harness runs through the REAL BGE hybrid path to
 * measure the ≥80% top-5 hit-rate (PRD §7.2 / SPEC-SEARCH §deferred golden set). This test cannot measure relevance
 * (that needs real embeddings on a device); it guarantees the fixture is well-formed, non-trivial, self-consistent,
 * and secret-free so the device run isn't wasted on a broken input.
 */
class GoldenRelevanceStructuralTest {
    @Serializable
    private data class Fixture(
        val papers: List<Paper>,
        val queries: List<Query>,
    )

    @Serializable
    private data class Paper(
        val id: String,
        val title: String,
        val abstract: String,
        val primaryCategory: String,
    )

    @Serializable
    private data class Query(
        val query: String,
        val expectedIds: List<String>,
    )

    private val fixture: Fixture by lazy {
        val json =
            requireNotNull(javaClass.getResourceAsStream("/golden_relevance.json")) {
                "golden_relevance.json missing from :core:search test resources"
            }.readBytes().decodeToString()
        Json.decodeFromString(Fixture.serializer(), json)
    }

    @Test
    fun `the corpus is large enough for a meaningful top-5 hit-rate`() {
        assertTrue(fixture.papers.size >= 25, "want >= 25 papers, got ${fixture.papers.size}")
        assertTrue(fixture.queries.size >= 20, "want >= 20 queries, got ${fixture.queries.size}")
    }

    @Test
    fun `paper ids are unique`() {
        val ids = fixture.papers.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate paper ids in the fixture")
    }

    @Test
    fun `every query names at least one expected id, and every expected id resolves to a paper`() {
        val ids = fixture.papers.map { it.id }.toSet()
        fixture.queries.forEach { q ->
            assertTrue(q.expectedIds.isNotEmpty(), "query '${q.query}' has no expected ids")
            q.expectedIds.forEach { expected ->
                assertTrue(expected in ids, "expected id '$expected' (query '${q.query}') is absent from papers")
            }
        }
    }

    @Test
    fun `papers have arXiv-shaped ids and real, non-empty content`() {
        val arxivId = Regex("""\d{4}\.\d{4,5}""")
        fixture.papers.forEach { p ->
            assertTrue(arxivId.matches(p.id), "id '${p.id}' is not a version-less arXiv id")
            assertTrue(p.title.isNotBlank(), "blank title for ${p.id}")
            assertTrue(p.primaryCategory.isNotBlank(), "blank primary category for ${p.id}")
            // A genuine abstract is a paragraph; a too-short one signals an invented/empty entry.
            assertTrue(
                p.abstract.length >= 120,
                "abstract for ${p.id} is implausibly short (${p.abstract.length} chars)",
            )
        }
    }

    @Test
    fun `no email or token-shaped secret leaks into the fixture (redaction red line)`() {
        val blob =
            fixture.papers.joinToString(" ") { "${it.title} ${it.abstract}" } +
                fixture.queries.joinToString(" ") { it.query }
        // URLs are legitimate transcribed abstract content (e.g. U-Net, DDPM); emails and API keys are not.
        val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        assertTrue(email.findAll(blob).none(), "an email address leaked into the golden fixture")
        val tokenShaped = Regex("""sk-[A-Za-z0-9]{16,}|AIza[A-Za-z0-9_-]{20,}|ghp_[A-Za-z0-9]{20,}""")
        assertTrue(tokenShaped.findAll(blob).none(), "a token-shaped secret leaked into the golden fixture")
    }
}
