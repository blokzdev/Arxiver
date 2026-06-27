package dev.blokz.arxiver.data

import android.content.Context
import java.io.File

/**
 * Single source of truth for the on-device PDF cache convention: `filesDir/pdfs/<id with / → _>v<version>.pdf`.
 * Used by the downloader/viewer, the vision page-image source, and the PS.5 "share PDF" action so the
 * filename scheme lives in exactly one place.
 */
object PdfStorage {
    /** The directory all downloaded PDFs live in (created lazily by the downloader). */
    fun dir(context: Context): File = File(context.filesDir, "pdfs")

    /** Newest already-downloaded PDF for [paperId] (any version), or null when none is on device. */
    fun localPdf(
        context: Context,
        paperId: String,
    ): File? {
        val prefix = paperId.replace('/', '_') + "v"
        return dir(context)
            .listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".pdf") && f.length() > 0 }
            ?.maxByOrNull { it.lastModified() }
    }
}
