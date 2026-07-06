package dev.blokz.arxiver.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class S2OriginTest {
    @Test
    fun `bioRxiv venue classifies to BIORXIV`() {
        assertEquals(Source.BIORXIV, s2OriginFromVenue("bioRxiv"))
    }

    @Test
    fun `medRxiv venue classifies to MEDRXIV`() {
        assertEquals(Source.MEDRXIV, s2OriginFromVenue("medRxiv"))
    }

    @Test
    fun `classification is case and whitespace tolerant`() {
        assertEquals(Source.MEDRXIV, s2OriginFromVenue(" MEDRXIV "))
        assertEquals(Source.BIORXIV, s2OriginFromVenue("BioRxiv (Cold Spring Harbor Laboratory)"))
    }

    @Test
    fun `medRxiv is matched before bioRxiv (ordering guard)`() {
        // "medrxiv" does not substring-contain "biorxiv", so medRxiv must never fall through to BIORXIV.
        assertEquals(Source.MEDRXIV, s2OriginFromVenue("medRxiv"))
    }

    @Test
    fun `a non-preprint venue is not first-class`() {
        assertNull(s2OriginFromVenue("NeurIPS"))
        assertNull(s2OriginFromVenue("Nature"))
        assertNull(s2OriginFromVenue(""))
    }

    @Test
    fun `a DOI as venue never classifies (DOI cannot discriminate)`() {
        // Locks the C2/C5 ruling: the 10.1101 prefix is shared bio/medRxiv — only `venue` discriminates.
        assertNull(s2OriginFromVenue("10.1101/2024.01.07.574543"))
    }

    @Test
    fun `null venue is null`() {
        assertNull(s2OriginFromVenue(null))
    }
}
