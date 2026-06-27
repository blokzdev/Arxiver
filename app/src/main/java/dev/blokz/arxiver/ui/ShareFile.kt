package dev.blokz.arxiver.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.blokz.arxiver.R
import java.io.File

/**
 * Share a cache file (e.g. an exported Markdown conversation, P-Share PS.6) via the OS chooser with
 * the given [mimeType]. User-initiated: the file lives in the app cache and is exposed through the
 * **scoped** FileProvider (`${applicationId}.fileprovider`) with a **per-share read grant** — nothing
 * leaves the device except to the target the user picks (no upload, the AI key is never involved).
 * Generic sibling of [shareImage] / [sharePdf]; the `ClipData` carries the grant to the chooser
 * preview process too (see [shareImage]).
 */
fun Context.shareFile(
    file: File,
    mimeType: String,
    subject: String? = null,
) {
    val uri = FileProvider.getUriForFile(this, "$packageName$EXPORT_FILEPROVIDER_SUFFIX", file)
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, subject ?: file.name, uri)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    startActivity(Intent.createChooser(send, getString(R.string.action_share)))
}
