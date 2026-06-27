package dev.blokz.arxiver.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.blokz.arxiver.R
import java.io.File

/** FileProvider authority suffix + the cache subdir exports live in (kept in sync with the manifest + file_paths.xml). */
const val EXPORT_FILEPROVIDER_SUFFIX = ".fileprovider"
const val EXPORT_DIR = "ask_exports"

/**
 * Share an exported image (a rendered answer / diagram, P-Share PS.4) via the OS chooser.
 * User-initiated: the file lives in the app cache and is exposed through the **scoped** FileProvider
 * (`${applicationId}.fileprovider`) with a **per-share read grant** — nothing leaves the device except
 * to the target the user picks (no upload, the AI key is never involved). Sibling of [shareText].
 */
fun Context.shareImage(
    file: File,
    subject: String? = null,
) {
    val uri = FileProvider.getUriForFile(this, "$packageName$EXPORT_FILEPROVIDER_SUFFIX", file)
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    startActivity(Intent.createChooser(send, getString(R.string.action_share)))
}
