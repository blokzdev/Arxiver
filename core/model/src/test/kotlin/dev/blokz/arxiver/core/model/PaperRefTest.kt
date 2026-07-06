package dev.blokz.arxiver.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The source-identity seam (P-Sources PS.0). The load-bearing guarantee is **byte-identity for arXiv**:
 * an `ArxivRef`'s `storageId` equals today's `ArxivId.value`, so no `papers.id` row is ever re-keyed,
 * and its URL methods are unchanged. `fromStorageId` is the exact inverse of the storage-id encoding —
 * un-prefixed ⇒ arXiv, a reserved prefix ⇒ the right `Source`, splitting on the first `:` only.
 */
class PaperRefTest {
    @Test
    fun `ArxivRef storageId is the bare arXiv id (byte-identical, zero re-key)`() {
        assertEquals("2403.09999", ArxivRef(ArxivId("2403.09999")).storageId)
        // Legacy ids with a slash and no colon stay bare, too.
        assertEquals("math.GT/0309136", ArxivRef(ArxivId("math.GT/0309136")).storageId)
    }

    @Test
    fun `ArxivRef delegates URL synthesis to ArxivId unchanged`() {
        val id = ArxivId("2403.01234")
        val ref = ArxivRef(id)
        assertEquals(id.absUrl(3), ref.absUrl(3))
        assertEquals(id.pdfUrl(3), ref.pdfUrl(3))
        assertEquals(id.htmlUrl(), ref.htmlUrl())
        assertEquals(Source.ARXIV, ref.origin)
        assertNull(ref.nativeId)
        assertEquals(id, ref.arxivIdOrNull)
    }

    @Test
    fun `fromStorageId of an un-prefixed id is an ArxivRef`() {
        val ref = PaperRef.fromStorageId("2403.09999")
        assertIs<ArxivRef>(ref)
        assertEquals("2403.09999", ref.storageId)
        // A legacy id (has a slash, no reserved prefix before the first colon) is still arXiv.
        assertIs<ArxivRef>(PaperRef.fromStorageId("math.GT/0309136"))
    }

    @Test
    fun `each reserved prefix maps to its Source as an ExternalRef`() {
        for (source in listOf(Source.CHEMRXIV, Source.BIORXIV, Source.MEDRXIV, Source.S2)) {
            val ref = PaperRef.fromStorageId("${source.wire}:native-123")
            assertIs<ExternalRef>(ref)
            assertEquals(source, ref.origin)
            assertEquals("native-123", ref.nativeId)
        }
    }

    @Test
    fun `an ExternalRef round-trips through the first colon, preserving inner colons and slashes`() {
        val ref = ExternalRef(Source.CHEMRXIV, "10.26434/chemrxiv-2024-xyz")
        assertEquals("chemrxiv:10.26434/chemrxiv-2024-xyz", ref.storageId)
        val back = PaperRef.fromStorageId(ref.storageId)
        assertEquals(ref, back)
        assertEquals("10.26434/chemrxiv-2024-xyz", back.nativeId)
        // A native id that itself contains a colon survives (only the first colon splits).
        val weird = ExternalRef(Source.S2, "a:b/c")
        assertEquals("a:b/c", PaperRef.fromStorageId(weird.storageId).nativeId)
    }

    @Test
    fun `storageId round-trips its own origin for every ref`() {
        val refs = listOf(ArxivRef(ArxivId("2403.09999")), ExternalRef(Source.CHEMRXIV, "10.26434/x"))
        for (ref in refs) {
            assertEquals(ref.origin, PaperRef.fromStorageId(ref.storageId).origin)
        }
    }

    @Test
    fun `an ExternalRef cannot carry Source ARXIV`() {
        assertFailsWith<IllegalArgumentException> { ExternalRef(Source.ARXIV, "2403.09999") }
    }

    // --- resolvePaperRef: the single de-dup chokepoint (SPEC-P-SOURCES §2 red line) ---

    @Test
    fun `resolvePaperRef keys a parseable arXiv id under the bare id, never a source alias`() {
        // An S2-shaped hit that carries an arXiv cross-id must resolve to the BARE arXiv id — no `s2:` fork.
        val ref = resolvePaperRef(arxivId = "2403.09999", origin = Source.S2, nativeId = "s2-paper-abc")
        assertIs<ArxivRef>(ref)
        assertEquals("2403.09999", ref.storageId)
    }

    @Test
    fun `resolvePaperRef accepts a versioned or URL-shaped arXiv id via ArxivId parse`() {
        // Same gate `import_to_library` uses: a versioned/URL form still resolves to the bare arXiv ref.
        assertEquals("2403.09999", resolvePaperRef("2403.09999v2", Source.S2, "x").storageId)
        assertEquals("2403.09999", resolvePaperRef("https://arxiv.org/abs/2403.09999", Source.S2, "x").storageId)
    }

    @Test
    fun `resolvePaperRef with no arXiv id keys under the source nativeId`() {
        val ref = resolvePaperRef(arxivId = null, origin = Source.CHEMRXIV, nativeId = "10.26434/chemrxiv-2024-xyz")
        assertIs<ExternalRef>(ref)
        assertEquals("chemrxiv:10.26434/chemrxiv-2024-xyz", ref.storageId)
    }

    @Test
    fun `resolvePaperRef falls through to ExternalRef when the arXiv candidate is unparseable`() {
        val ref = resolvePaperRef(arxivId = "not-an-arxiv-id", origin = Source.CHEMRXIV, nativeId = "10.26434/x")
        assertIs<ExternalRef>(ref)
        assertEquals("chemrxiv:10.26434/x", ref.storageId)
    }
}
