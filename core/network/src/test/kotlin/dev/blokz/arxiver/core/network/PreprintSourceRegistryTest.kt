package dev.blokz.arxiver.core.network

import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.network.biorxiv.BIORXIV_CATEGORIES
import dev.blokz.arxiver.core.network.biorxiv.MEDRXIV_CATEGORIES
import dev.blokz.arxiver.core.network.openalex.OPENALEX_FIELDS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    // --- P-Explorer PE.1: per-source curated vocabularies, derived from live OpenAlex field distributions ---

    private fun tokensOf(source: Source): List<String> =
        assertNotNull(PreprintSourceRegistry.infoFor(source)).categories.map { it.value }

    @Test
    fun `chemRxiv drops the disciplines it barely publishes in (the device-reported incoherence)`() {
        val t = tokensOf(Source.CHEMRXIV)
        // Measured 2026-07-10 (2024+ works): Dentistry 0.04%, Arts & Humanities 0.10%, Veterinary 0.02%.
        assertTrue("fields/35" !in t, "Dentistry must not be offered for a chemistry server")
        assertTrue("fields/12" !in t, "Arts and Humanities must not be offered for a chemistry server")
        assertTrue("fields/34" !in t, "Veterinary must not be offered for a chemistry server")
        assertTrue("fields/32" !in t, "Psychology (0.06%) is noise for chemRxiv")
    }

    @Test
    fun `chemRxiv keeps Computer Science and Medicine — they are real mass, not noise`() {
        val t = tokensOf(Source.CHEMRXIV)
        // The intuitive "chemistry-adjacent" subset omitted both; they carry 4.68% (comp-chem) and 3.85% (med-chem).
        assertTrue("fields/17" in t, "Computer Science is 4.68% of recent chemRxiv works")
        assertTrue("fields/27" in t, "Medicine is 3.85% of recent chemRxiv works")
        assertEquals("fields/16", t.first(), "the picker leads with Chemistry — curated lists are in real-mass order")
    }

    @Test
    fun `SSRN leads with Engineering, not Economics — it is a broad STEM firehose now`() {
        val t = tokensOf(Source.SSRN)
        // Counter-intuitive but measured: Engineering 21.87% of 2024+ works; Economics only 7.75%.
        assertEquals("fields/22", t.first(), "Engineering is SSRN's #1 recent field")
        assertTrue("fields/20" in t, "Economics/Econometrics/Finance is still real (7.75%)")
        assertTrue("fields/17" in t, "Computer Science is 7.43% — the legacy law/econ subset would have hidden it")
        assertTrue("fields/23" in t, "Environmental Science is 7.56%")
        assertTrue("fields/35" !in t, "Dentistry (0.09%) is below the 1% floor")
    }

    @Test
    fun `PsyArXiv keeps Medicine — clinical psychology is 9 percent of it`() {
        val t = tokensOf(Source.PSYARXIV)
        assertEquals("fields/32", t.first(), "Psychology leads (40.3%)")
        assertTrue("fields/27" in t, "Medicine is 9.04% — the one real omission in the intuitive 3-field set")
        assertTrue("fields/16" !in t, "Chemistry (0.02%) is noise for PsyArXiv")
        assertTrue("fields/25" !in t, "Materials Science (0.02%) is noise for PsyArXiv")
    }

    @Test
    fun `the megajournals keep all 26 Fields — their distributions are genuinely flat`() {
        // Research Square (Springer Nature) + Preprints.org (MDPI): top-10 covers only ~80-84% and every Field
        // carries >0.15% of works, so curating would hide real content rather than remove noise.
        listOf(Source.RESEARCH_SQUARE, Source.PREPRINTS_ORG).forEach { source ->
            assertEquals(26, tokensOf(source).size, "${source.wire} is a megajournal — do not curate it")
        }
    }

    @Test
    fun `every curated token resolves to a real OpenAlex Field and none repeat`() {
        val known = OPENALEX_FIELDS.map { it.token }.toSet()
        listOf(Source.CHEMRXIV, Source.SSRN, Source.PSYARXIV, Source.RESEARCH_SQUARE, Source.PREPRINTS_ORG)
            .forEach { source ->
                val t = tokensOf(source)
                assertTrue(t.all { it in known }, "${source.wire} offers a token that is not a real Field")
                assertEquals(t.size, t.toSet().size, "${source.wire} repeats a Field")
            }
    }
}
