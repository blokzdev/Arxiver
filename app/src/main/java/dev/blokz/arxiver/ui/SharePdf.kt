package dev.blokz.arxiver.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.blokz.arxiver.R
import java.io.File

/**
 * Share an already-downloaded paper PDF (P-Share PS.5) via the OS chooser. User-initiated: the file
 * lives in `filesDir/pdfs/` and is exposed through the **scoped** FileProvider
 * (`${applicationId}.fileprovider`, `file_paths.xml` → `files-path pdfs/`) with a **per-share read
 * grant** — nothing leaves the device except to the target the user picks (no upload, the AI key is
 * never involved). Sibling of [shareImage] / [shareText]; the `ClipData` carries the same grant to
 * the chooser preview process (see [shareImage] for why it's required).
 */
fun Context.sharePdf(
    file: File,
    subject: String? = null,
) {
    val uri = FileProvider.getUriForFile(this, "$packageName$EXPORT_FILEPROVIDER_SUFFIX", file)
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, subject ?: "document", uri)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    startActivity(Intent.createChooser(send, getString(R.string.action_share)))
}
