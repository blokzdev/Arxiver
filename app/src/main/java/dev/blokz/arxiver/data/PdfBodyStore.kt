package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A cached PDF that carries an authoritative storageId sidecar + its body-index marker (Phase P-Reader2
 * PFT.5.5). [marker] is `OK:<model>` (body-indexed & readable), `SKIP:<gateVersion>` (extracted but the quality
 * gate rejected it), or null (never attempted).
 */
data class CachedPdfRef(
    val storageId: String,
    val version: Int,
    val marker: String?,
)

/**
 * Filesystem sidecar store for PDF body-indexing state (Phase P-Reader2 PFT.5.5), mirroring `HtmlStorage`
 * discipline over the existing `filesDir/pdfs/` cache ([PdfStorage]). Context-free (takes `filesDir`) so it is
 * JVM/Robolectric-testable, like `HtmlStorage`.
 *
 * Two sidecars live next to `<safeName>.pdf` — and because [PdfStorage.safeName] already appends `.pdf`, they
 * are `…v2.pdf.id` and `…v2.pdf.bodyindex`, both **invisible** to `PdfStorage.localPdf`'s `endsWith(".pdf")`
 * glob (and to `.pdf.part` download temps):
 *  - **`.id`** — the authoritative `PaperRef.storageId`. Reverse-parsing the id from the filename is UNSOUND:
 *    `PdfStorage.UNSAFE = [/:]` maps both `/` and `:` to `_` non-injectively, and non-arXiv ids carry `:`. So
 *    the backfill (which enumerates files, not the DB) reads the id from here, never from the name.
 *  - **`.bodyindex`** — the `OK:<model>` | `SKIP:<gateVersion>` marker. `SKIP` is keyed on the GATE version
 *    (garbage is model-independent — a mere embedding-model bump must not re-attempt it; only a gate
 *    improvement should), while `OK` is keyed on the model (a model bump re-embeds).
 */
class PdfBodyStore(
    private val filesDir: File,
    private val dispatchers: DispatcherProvider,
) {
    private fun dir(): File = File(filesDir, "pdfs")

    /** The newest already-downloaded PDF for [storageId] (Context-free twin of [PdfStorage.localPdf]). */
    fun localPdf(storageId: String): File? {
        val prefix = storageId.replace(UNSAFE, "_") + "v"
        return dir()
            .listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".pdf") && f.length() > 0 }
            ?.maxByOrNull { parseVersion(it.name) ?: -1 }
    }

    /** Write the authoritative storageId sidecar for a cached PDF version (idempotent; atomic tmp→move). */
    suspend fun storeId(
        storageId: String,
        version: Int,
    ): Unit =
        withContext(dispatchers.io) {
            writeSidecar(sidecar(storageId, version, ID), storageId)
        }

    /** The storageId recorded next to [pdfFile], or null when the sidecar is absent/unreadable. */
    fun readId(pdfFile: File): String? =
        runCatching { File(pdfFile.path + ID).readText().trim().ifEmpty { null } }.getOrNull()

    /** Stamp the body-index marker (`OK:<model>` | `SKIP:<gateVersion>`) after an index attempt. Atomic. */
    suspend fun storeBodyIndex(
        storageId: String,
        version: Int,
        marker: String,
    ): Unit =
        withContext(dispatchers.io) {
            writeSidecar(sidecar(storageId, version, BODY_INDEX), marker)
        }

    /** The body-index marker for an exact version, or null (never attempted / unreadable). */
    fun readBodyIndex(
        storageId: String,
        version: Int,
    ): String? = runCatching { sidecar(storageId, version, BODY_INDEX).readText().trim().ifEmpty { null } }.getOrNull()

    /**
     * Every cached PDF that has an authoritative `.id` sidecar, **de-duped to the newest version per storageId**
     * (else v1 and v2 both target the one `source_kind='body'` slot). Enumerates the filesystem — never the
     * paper table — so a paper with no cached PDF is simply not a candidate (mirrors `HtmlStorage.cachedBodies`).
     * PDFs lacking a `.id` (pre-PFT.5 downloads) are skipped here; the one-time id-backfill (PFT.5.7) fills them.
     */
    fun cachedPdfs(): List<CachedPdfRef> =
        dir().listFiles { f -> f.isFile && f.name.endsWith(".pdf") && f.length() > 0 }
            ?.mapNotNull { pdf ->
                val storageId = readId(pdf) ?: return@mapNotNull null
                val version = parseVersion(pdf.name) ?: return@mapNotNull null
                CachedPdfRef(storageId, version, readBodyIndex(storageId, version))
            }
            .orEmpty()
            .groupBy { it.storageId }
            .map { (_, refs) -> refs.maxBy { it.version } }

    /**
     * One-time id-backfill (PFT.5.7): for each cached PDF lacking a `.pdf.id`, recover its storageId via
     * [resolve] (keyed on the filename's sanitized prefix — the sound forward match) and write the sidecar, so
     * the pre-existing downloaded corpus is covered by the worker backfill without waiting for a re-open.
     * Idempotent — a PDF that already has a `.id` is skipped, so subsequent passes are cheap no-ops.
     * @return the number of ids newly written.
     */
    suspend fun backfillMissingIds(resolve: (sanitizedPrefix: String) -> String?): Int =
        withContext(dispatchers.io) {
            val orphans =
                dir().listFiles { f -> f.isFile && f.name.endsWith(".pdf") && f.length() > 0 }
                    ?.filter { !File(it.path + ID).exists() }
                    .orEmpty()
            var written = 0
            for (pdf in orphans) {
                val sanitized = pdf.name.removeSuffix(".pdf").substringBeforeLast('v')
                val storageId = resolve(sanitized) ?: continue
                writeSidecar(File(pdf.path + ID), storageId)
                written++
            }
            written
        }

    private fun sidecar(
        storageId: String,
        version: Int,
        suffix: String,
    ): File = File(dir(), PdfStorage.safeName(storageId, version) + suffix)

    private fun writeSidecar(
        target: File,
        content: String,
    ) {
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".part")
            tmp.writeText(content)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Version from a `…v<digits>.pdf` name — the LAST `v` is `safeName`'s version separator. */
    private fun parseVersion(pdfName: String): Int? = pdfName.removeSuffix(".pdf").substringAfterLast('v').toIntOrNull()

    private companion object {
        // Mirror PdfStorage.UNSAFE so localPdf globs the same name the downloader wrote.
        val UNSAFE = Regex("""[/:]""")
        const val ID = ".id"
        const val BODY_INDEX = ".bodyindex"
    }
}
