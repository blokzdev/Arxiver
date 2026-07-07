package dev.blokz.arxiver.core.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DOI normalization for cross-source de-dup (P-FeedPolish): prefix strip, lowercase, scoped `.vN` strip. */
class NormalizeDoiTest {
    @Test
    fun `strips resolver prefixes and lowercases`() {
        assertEquals("10.1101/2024.01.02.680000", normalizeDoi("https://doi.org/10.1101/2024.01.02.680000"))
        assertEquals("10.1101/x", normalizeDoi("10.1101/X"))
        assertEquals("10.26434/chemrxiv-2024-9lpb9", normalizeDoi("doi:10.26434/chemrxiv-2024-9lpb9"))
    }

    @Test
    fun `strips only a scoped dot-v-digits version suffix`() {
        // chemRxiv version baked into the DOI → stripped.
        assertEquals("10.26434/chemrxiv.7234721", normalizeDoi("10.26434/chemrxiv.7234721.v5"))
        // A legitimate DOI merely ending in a dotted number (no `v`) is NOT touched.
        assertEquals("10.1101/2024.01.02.680000", normalizeDoi("10.1101/2024.01.02.680000"))
        // The other chemRxiv shape (version only in locations, not the DOI) is unchanged.
        assertEquals("10.26434/chemrxiv-2024-9lpb9", normalizeDoi("10.26434/chemrxiv-2024-9lpb9"))
    }

    @Test
    fun `a blank or absent DOI is never a cross-merge key`() {
        assertNull(normalizeDoi(null))
        assertNull(normalizeDoi(""))
        assertNull(normalizeDoi("   "))
        assertNull(normalizeDoi("https://doi.org/"))
    }
}
