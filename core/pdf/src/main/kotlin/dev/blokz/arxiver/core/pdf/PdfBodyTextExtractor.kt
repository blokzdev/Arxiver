package dev.blokz.arxiver.core.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Extracts plain body text from an **already-downloaded local PDF** (Phase P-Reader2, PFT.5) behind the
 * File-seam that `BodyIndexer`'s PDF path calls. Runs entirely on-device over a local [File] — pdfbox never
 * touches the network (guarded by [PdfboxNoNetworkStructuralTest]). Output is whitespace-collapsed to match
 * `HtmlBodyTextExtractor`'s shape, so both feed identical `source_kind='body'` chunks.
 *
 * Robustness: `PDDocument.load(File)` auto-decrypts with the **empty user password**, so a permission-flagged
 * but readable published PDF still extracts; a genuinely password-locked or malformed doc throws → `""`
 * (never a crash). A math-only / scanned / figure-only body yields little or no usable text — the caller's
 * quality gate (PFT.5.4) rejects garbage before it embeds or counts toward coverage.
 */
class PdfBodyTextExtractor(
    appContext: Context,
    private val dispatchers: DispatcherProvider,
    /**
     * PROVISIONAL — device-A/B-ratified (PFT.5.2 VERIFICATION). `false` = the PDF's content-stream order
     * (arXiv pdflatex output is usually column-major and reads correctly); `true` sorts glyphs by position,
     * which can interleave a two-column layout. The garbage-text gate (PFT.5.4) is the safety net either way.
     */
    private val sortByPosition: Boolean = DEFAULT_SORT_BY_POSITION,
) {
    // Hold the application context only (never an Activity) — this extractor outlives any screen.
    private val appContext: Context = appContext.applicationContext

    /** @return whitespace-collapsed body text, or `""` for a locked/malformed/text-free PDF (never throws). */
    suspend fun extract(file: File): String =
        withContext(dispatchers.io) {
            ensureInit(appContext)
            val raw =
                runCatching {
                    PDDocument.load(file).use { doc ->
                        PDFTextStripper()
                            .apply { sortByPosition = this@PdfBodyTextExtractor.sortByPosition }
                            .getText(doc)
                    }
                }.getOrDefault("")
            collapseWhitespace(raw)
        }

    companion object {
        const val DEFAULT_SORT_BY_POSITION = false

        private val initLock = Any()

        @Volatile
        private var initialized = false

        /**
         * `PDFBoxResourceLoader.init` is a required, global, one-time setup (loads the bundled font/glyph
         * resources from the FULL local-asset bundle — no font egress). Idempotent + cheap after the first
         * call; synchronized so a reader-open trigger and the background worker can extract concurrently
         * without a double-init race.
         */
        private fun ensureInit(context: Context) {
            if (initialized) return
            synchronized(initLock) {
                if (!initialized) {
                    PDFBoxResourceLoader.init(context)
                    initialized = true
                }
            }
        }

        /**
         * Collapse every whitespace run (spaces, tabs, newlines, form-feeds) to a single space and trim —
         * matches the jsoup `body().text()` shape `HtmlBodyTextExtractor` produces, so PDF- and HTML-derived
         * body chunks are indistinguishable to the chunker/BM25. Pure; unit-tested.
         */
        internal fun collapseWhitespace(text: String): String = text.replace(WHITESPACE, " ").trim()

        private val WHITESPACE = Regex("""\s+""")
    }
}
