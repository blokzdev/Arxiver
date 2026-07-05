package dev.blokz.arxiver.data

import android.content.Context
import java.io.File

/**
 * Single source of truth for the on-device PDF cache convention: `filesDir/pdfs/<sanitized id>v<version>.pdf`.
 * Used by the downloader/viewer, the vision page-image source, and the PS.5 "share PDF" action so the
 * filename scheme lives in exactly one place.
 *
 * The [UNSAFE] sanitizer maps BOTH `/` and `:` to `_`: a non-arXiv `storageId` is `"<origin>:<nativeId>"`
 * (P-Sources PS.1), and `:` is illegal in an Android filename. The writer ([safeName]) and every reader
 * ([localPdf], and the vision page-image source that goes through it) MUST share this exact regex — else a
 * reader globs for a `:`-named file the writer never wrote. The map is non-injective (`a/b` and `a:b` both
 * → `a_b`); that collision was already possible for legacy arXiv ids and is accepted (no hash prefix).
 */
object PdfStorage {
    private val UNSAFE = Regex("""[/:]""")

    /** The directory all downloaded PDFs live in (created lazily by the downloader). */
    fun dir(context: Context): File = File(context.filesDir, "pdfs")

    /** The on-device filename for [paperId] (a `PaperRef.storageId`) at [version]. The one writer-side name. */
    fun safeName(
        paperId: String,
        version: Int,
    ): String = paperId.replace(UNSAFE, "_") + "v$version.pdf"

    /** Newest already-downloaded PDF for [paperId] (any version), or null when none is on device. */
    fun localPdf(
        context: Context,
        paperId: String,
    ): File? {
        val prefix = paperId.replace(UNSAFE, "_") + "v"
        return dir(context)
            .listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".pdf") && f.length() > 0 }
            ?.maxByOrNull { it.lastModified() }
    }
}
