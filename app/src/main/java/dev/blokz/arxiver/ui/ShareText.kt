package dev.blokz.arxiver.ui

import android.content.Context
import android.content.Intent
import dev.blokz.arxiver.R

/**
 * Share plain text via the OS chooser (P-Rich R4). User-initiated; nothing leaves the device except
 * to the target the user picks. Consolidates the `ACTION_SEND` + `EXTRA_TEXT` + chooser pattern
 * already duplicated for the paper link, library export, and backup shares.
 */
fun Context.shareText(
    text: String,
    subject: String? = null,
) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
    startActivity(Intent.createChooser(send, getString(R.string.action_share)))
}
