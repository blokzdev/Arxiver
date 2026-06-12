package dev.blokz.arxiver.feature.claude

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Copies a paste-ready routine instruction block (generic or per-template). */
internal fun copyInstructionsToClipboard(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Arxiver routine instructions", text))
}
