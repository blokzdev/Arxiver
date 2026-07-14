package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PFT.5.5 — the PDF sidecar store: authoritative id, markers, newest-version de-dup, sidecar invisibility. */
class PdfBodyStoreTest {
    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.Unconfined
            override val default: CoroutineDispatcher = Dispatchers.Unconfined
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
        }
    private val filesDir: File = Files.createTempDirectory("pdfstore").toFile()
    private val store = PdfBodyStore(filesDir, dispatchers)
    private val pdfDir = File(filesDir, "pdfs").apply { mkdirs() }

    @AfterTest
    fun cleanup() {
        filesDir.deleteRecursively()
    }

    private fun writePdf(
        storageId: String,
        version: Int,
    ): File = File(pdfDir, PdfStorage.safeName(storageId, version)).apply { writeText("%PDF-1.4 body") }

    @Test
    fun `id sidecar round-trips even for a non-arXiv storageId with a colon`() {
        val sid = "chemrxiv:10.26434/x" // the exact case filename reverse-parsing can't recover
        val pdf = writePdf(sid, 2)
        runBlocking { store.storeId(sid, 2) }
        assertEquals(sid, store.readId(pdf))
    }

    @Test
    fun `body-index marker round-trips`() {
        runBlocking { store.storeBodyIndex("2401.00001", 1, "OK:bge-A") }
        assertEquals("OK:bge-A", store.readBodyIndex("2401.00001", 1))
        assertNull(store.readBodyIndex("2401.00001", 2))
    }

    @Test
    fun `cachedPdfs de-dupes to the newest version per storageId`() {
        val sid = "2401.00001"
        writePdf(sid, 1)
        writePdf(sid, 2)
        runBlocking {
            store.storeId(sid, 1)
            store.storeId(sid, 2)
        }
        val cached = store.cachedPdfs()
        assertEquals(1, cached.size, "one entry per storageId")
        assertEquals(2, cached.single().version, "the newest version wins the single body slot")
    }

    @Test
    fun `cachedPdfs skips a PDF with no id sidecar (pre-PFT5 download)`() {
        writePdf("2401.00009", 1) // no .id sidecar written
        assertTrue(store.cachedPdfs().isEmpty())
    }

    @Test
    fun `backfillMissingIds recovers ids for pre-existing PDFs, idempotently`() {
        // A non-arXiv id (colon → the reverse-parse-unsound case) and an arXiv id, both without a .pdf.id yet.
        val chem = "chemrxiv:10.26434/widget"
        writePdf(chem, 1)
        writePdf("2401.00001", 2)
        val bySanitized = listOf(chem, "2401.00001").associateBy { it.replace(Regex("""[/:]"""), "_") }

        val written = runBlocking { store.backfillMissingIds { bySanitized[it] } }

        assertEquals(2, written)
        assertEquals(chem, store.readId(File(pdfDir, PdfStorage.safeName(chem, 1))))
        assertEquals("2401.00001", store.readId(File(pdfDir, PdfStorage.safeName("2401.00001", 2))))
        // Idempotent: a second pass finds nothing to write.
        assertEquals(0, runBlocking { store.backfillMissingIds { bySanitized[it] } })
    }

    @Test
    fun `sidecars and part temps are invisible to localPdf`() {
        val sid = "2401.00001"
        val pdf = writePdf(sid, 1)
        runBlocking {
            store.storeId(sid, 1)
            store.storeBodyIndex(sid, 1, "OK:bge-A")
        }
        File(pdfDir, PdfStorage.safeName(sid, 1) + ".part").writeText("partial")
        assertEquals(pdf.name, store.localPdf(sid)?.name, "only the real .pdf, never .id/.bodyindex/.part")
    }
}
