package dev.blokz.arxiver.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the PF.3 new-source [Source] values. A [Source.wire] is a PERMANENT storage-id/`origin` prefix — there
 * is no migration to rename it after data exists — so this pins that each new wire is registered in
 * [Source.BY_PREFIX] and round-trips losslessly through the PK, and that all wires stay unique + prefix-safe.
 */
class SourceTest {
    private val pf3 =
        listOf(Source.RESEARCH_SQUARE, Source.SSRN, Source.PREPRINTS_ORG, Source.PSYARXIV)

    @Test
    fun `each new PF3 source is in BY_PREFIX and round-trips through the storage id`() {
        pf3.forEach { s ->
            assertEquals(s, Source.BY_PREFIX[s.wire], "${s.wire} must resolve via BY_PREFIX")
            val ref = ExternalRef(s, "10.1234/abc.v2")
            assertEquals("${s.wire}:10.1234/abc.v2", ref.storageId)
            assertEquals(ref, PaperRef.fromStorageId(ref.storageId), "PK round-trip for ${s.wire}")
        }
    }

    @Test
    fun `every wire is unique, lowercase, and free of the reserved delimiters`() {
        val wires = Source.entries.map { it.wire }
        assertEquals(wires.size, wires.toSet().size, "wire tokens must be unique")
        wires.forEach { w ->
            assertEquals(w.lowercase(), w, "wire must be lowercase: $w")
            assertTrue(':' !in w && '/' !in w && ' ' !in w, "wire must be delimiter-safe: $w")
        }
    }
}
