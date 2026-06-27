package dev.blokz.arxiver.ui.markdown

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Renders a whole conversation to a paginated **PDF** through the Android system print pipeline
 * (P-Share PS.6). The conversation Markdown (`ConversationMarkdown.conversation`) is rendered to the
 * same rich HTML the chat uses ([RichHtml]), so math / diagrams / tables print as *rendered* — but on
 * a fixed **light/print** theme (a dark PDF wastes ink and reads poorly). Pagination is the print
 * framework's job — this is where PDF beats the single-bitmap PNG of PS.4b for a tall conversation.
 *
 * The KaTeX/Mermaid async-render race is handled exactly like the PNG export (PS.4b): the WebView
 * renders attached-but-imperceptible (`alpha = 0.004`, non-zero so the compositor keeps producing
 * frames), and the host polls **host-initiated** `evaluateJavascript` for render-complete before
 * handing the WebView's print adapter to the [PrintManager]. The WebView is kept alive until the print
 * job finishes (the framework drives the adapter asynchronously), then detached + destroyed.
 *
 * Returns true once the print job is dispatched, false on any failure (no Activity / print service,
 * render never readied) so the caller can fall back — never a crash. User-initiated, offline (only
 * bundled KaTeX/Mermaid assets via [applyRichSandbox]'s `blockNetworkLoads`), no upload, key untouched.
 */
object ConversationPdfPrinter {
    private const val POLL_INTERVAL_MS = 250L
    private const val MAX_WAIT_MS = 8_000L
    private const val FINAL_FLUSH_MS = 160L

    // Mirrors RichImageExporter's readiness probe (kept local to leave the device-verified PS.4b
    // exporter untouched): 1 once web fonts loaded AND every Mermaid block has its <svg>, else -1.
    private const val READY_JS =
        "(function(){try{" +
            "if(document.fonts&&document.fonts.status!=='loaded')return -1;" +
            "var m=document.querySelectorAll('pre.mermaid');" +
            "for(var i=0;i<m.length;i++){if(!m[i].querySelector('svg'))return -1;}" +
            "return 1;}catch(e){return -1;}})()"

    // A printed/PDF page should be light regardless of the app's dark mode.
    private val PRINT_THEME =
        RichTheme(
            textColor = "#1a1a1a",
            citationColor = "#1565c0",
            codeBackground = "#f1f1f1",
            mutedColor = "#9e9e9e",
            crossRefColor = "#1565c0",
            dark = false,
            backgroundArgb = 0xFFFFFFFF.toInt(),
        )

    suspend fun print(
        context: Context,
        markdown: String,
        jobName: String,
    ): Boolean =
        withContext(Dispatchers.Main) {
            val activity = context as? Activity ?: return@withContext false
            val root =
                activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                    ?: return@withContext false
            val printManager =
                activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                    ?: return@withContext false
            val html =
                RichHtml.answerHtml(
                    markdown = markdown,
                    textColor = PRINT_THEME.textColor,
                    citationColor = PRINT_THEME.citationColor,
                    codeBackground = PRINT_THEME.codeBackground,
                    mutedColor = PRINT_THEME.mutedColor,
                    dark = PRINT_THEME.dark,
                    crossRefColor = PRINT_THEME.crossRefColor,
                )
            val webView = WebView(activity)
            webView.applyRichSandbox()
            webView.setBackgroundColor(PRINT_THEME.backgroundArgb)
            webView.alpha = 0.004f // imperceptible, but non-zero so the compositor keeps rendering
            webView.webViewClient =
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean = true // block the on-screen arxiver://height self-size pings
                }
            // Attach so the page lays out + renders (a never-attached WebView produces no frames).
            val widthPx = activity.resources.displayMetrics.widthPixels
            val heightPx = activity.resources.displayMetrics.heightPixels
            root.addView(webView, android.view.ViewGroup.LayoutParams(widthPx, heightPx))
            webView.loadDataWithBaseURL("file:///android_asset/katex/", html, "text/html", "utf-8", null)
            var waited = 0L
            while (waited < MAX_WAIT_MS) {
                delay(POLL_INTERVAL_MS)
                waited += POLL_INTERVAL_MS
                if ((webView.evalInt(READY_JS) ?: -1) > 0) break
            }
            delay(FINAL_FLUSH_MS)
            val cleanup = {
                runCatching {
                    root.removeView(webView)
                    webView.destroy()
                }
                Unit
            }
            val dispatched =
                runCatching {
                    val adapter = webView.createPrintDocumentAdapter(jobName)
                    val attributes =
                        PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                            .build()
                    printManager.print(jobName, CleanupAdapter(adapter, cleanup), attributes)
                    true
                }.getOrDefault(false)
            if (!dispatched) cleanup()
            dispatched
        }

    /** Bridges host-initiated `evaluateJavascript` to a suspend Int result (mirrors RichImageExporter). */
    private suspend fun WebView.evalInt(js: String): Int? =
        suspendCancellableCoroutine { cont ->
            evaluateJavascript(js) { result ->
                if (cont.isActive) cont.resume(result?.trim()?.removeSurrounding("\"")?.toDoubleOrNull()?.toInt())
            }
        }

    /**
     * Wraps the WebView's print adapter so the WebView is detached + destroyed once the print job
     * finishes (the framework drives the adapter asynchronously after [PrintManager.print] returns).
     */
    private class CleanupAdapter(
        private val delegate: PrintDocumentAdapter,
        private val onFinished: () -> Unit,
    ) : PrintDocumentAdapter() {
        override fun onStart() = delegate.onStart()

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback?,
            extras: Bundle?,
        ) = delegate.onLayout(oldAttributes, newAttributes, cancellationSignal, callback, extras)

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback?,
        ) = delegate.onWrite(pages, destination, cancellationSignal, callback)

        override fun onFinish() {
            delegate.onFinish()
            onFinished()
        }
    }
}
