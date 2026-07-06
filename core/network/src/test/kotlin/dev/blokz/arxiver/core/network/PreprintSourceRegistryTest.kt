package dev.blokz.arxiver.core.network

import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.network.biorxiv.BIORXIV_CATEGORIES
import dev.blokz.arxiver.core.network.biorxiv.MEDRXIV_CATEGORIES
import dev.blokz.arxiver.core.network.openalex.OPENALEX_FIELDS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the PF.3 category vocabularies. A wrong OpenAlex Field id fails SILENTLY (HTTP 200, count=0 —
 * live-verified), so the id→name table is structurally pinned here; a transposed digit fails at CI, not as a
 * mysteriously-empty follow. Also asserts the registry shape and that no vocabulary requires a network fetch.
 */
class PreprintSourceRegistryTest {
    @Test
    fun `the 26 OpenAlex Fields are pinned id-to-name (Chemistry is 16, not 23)`() {
        assertEquals(26, OPENALEX_FIELDS.size)
        val byToken = OPENALEX_FIELDS.associate { it.token to it.displayName }
        // Live-verified 2026-07-06 against api.openalex.org/fields — the dangerous ones especially.
        assertEquals("Chemistry", byToken["fields/16"])
        assertEquals("Environmental Science", byToken["fields/23"])
        assertEquals("Computer Science", byToken["fields/17"])
        assertEquals("Medicine", byToken["fields/27"])
        assertEquals("Psychology", byToken["fields/32"])
        // Every token is a well-formed `fields/N`, and all are unique.
        OPENALEX_FIELDS.forEach { assertTrue(Regex("""fields/\d+""").matches(it.token), it.token) }
        assertEquals(26, OPENALEX_FIELDS.map { it.token }.toSet().size)
    }

    @Test
    fun `bio and med category tables are the expected byte-exact size and shape`() {
        assertEquals(27, BIORXIV_CATEGORIES.size)
        assertTrue("neuroscience" in BIORXIV_CATEGORIES)
        assertTrue("cancer biology" in BIORXIV_CATEGORIES) // multi-word, space form the API accepts
        assertTrue(MEDRXIV_CATEGORIES.size >= 40)
        assertTrue("cardiovascular medicine" in MEDRXIV_CATEGORIES)
        // All bio/med tokens are lowercase (byte-match the api.biorxiv.org server strings).
        (BIORXIV_CATEGORIES + MEDRXIV_CATEGORIES).forEach { assertEquals(it.lowercase(), it) }
    }

    @Test
    fun `the registry offers the non-arXiv sources with a non-empty, correctly-shaped vocabulary`() {
        val sources = PreprintSourceRegistry.pickable.map { it.source }.toSet()
        assertEquals(
            setOf(
                Source.BIORXIV,
                Source.MEDRXIV,
                Source.CHEMRXIV,
                Source.RESEARCH_SQUARE,
                Source.SSRN,
                Source.PREPRINTS_ORG,
                Source.PSYARXIV,
            ),
            sources,
        )
        // arXiv keeps its own taxonomy grid; S2 is not a followable feed.
        assertTrue(PreprintSourceRegistry.infoFor(Source.ARXIV) == null)
        assertTrue(PreprintSourceRegistry.infoFor(Source.S2) == null)

        PreprintSourceRegistry.pickable.forEach { info ->
            assertTrue(info.categories.isNotEmpty(), "${info.source.wire} has no categories")
            assertTrue(info.allowsWholeSource, "${info.source.wire} should allow a whole-source follow")
            val openAlexServed = openAlexTokensExpected(info.source)
            info.categories.forEach { opt ->
                if (openAlexServed) {
                    assertTrue(opt.value.startsWith("fields/"), "${info.source.wire}: ${opt.value}")
                } else {
                    assertEquals(opt.value.lowercase(), opt.value) // bio/med server string
                }
            }
        }
    }

    private fun openAlexTokensExpected(source: Source): Boolean =
        source in
            setOf(Source.CHEMRXIV, Source.RESEARCH_SQUARE, Source.SSRN, Source.PREPRINTS_ORG, Source.PSYARXIV)
}
