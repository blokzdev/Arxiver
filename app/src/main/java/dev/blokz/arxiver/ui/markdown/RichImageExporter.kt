package dev.blokz.arxiver.ui.markdown

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.graphics.createBitmap
import dev.blokz.arxiver.ui.EXPORT_DIR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * Renders a rich AI answer to a **PNG** off-screen, for "Export as image" (P-Share PS.4b).
 *
 * The async-render race (KaTeX/Mermaid settle *after* `onPageFinished`) is solved by a **host-driven**
 * capture: the WebView renders the normal answer HTML attached but imperceptible (`alpha = 0.004`, so
 * the compositor still produces frames — a fully-transparent off-screen WebView is frame-culled, which
 * starves the async render), and the Kotlin host owns the timing — it polls **host-initiated**
 * `evaluateJavascript` for render-complete (web fonts loaded + every Mermaid `<svg>` present), then
 * snapshots. Nothing depends on the renderer's own page timers / `requestAnimationFrame`, which an
 * off-screen, occluded page throttles to a standstill (device-verified K17). The capture WebView
 * shares the offline [applyRichSandbox] (`blockNetworkLoads = true`) and is removed + destroyed before
 * this returns.
 *
 * Returns the saved file in `cache/ask_exports/` (the [shareImage] FileProvider dir), or **null** on
 * any failure (no Activity/window, render never readied within the timeout, draw/write failed) so the
 * caller can fall back gracefully — never a crash.
 */
object RichImageExporter {
    private const val POLL_INTERVAL_MS = 250L
    private const val MAX_WAIT_MS = 8_000L
    private const val FINAL_FLUSH_MS = 160L

    // Host-initiated readiness probe: 1 once web fonts have loaded AND every Mermaid block has its
    // <svg>, else -1. Host-initiated eval runs even while the renderer throttles its *own* page timers,
    // so this drives capture reliably (device-verified K17).
    private const val READY_JS =
        "(function(){try{" +
            "if(document.fonts&&document.fonts.status!=='loaded')return -1;" +
            "var m=document.querySelectorAll('pre.mermaid');" +
            "for(var i=0;i<m.length;i++){if(!m[i].querySelector('svg'))return -1;}" +
            "return 1;}catch(e){return -1;}})()"

    // Full content height in CSS px = the lowest pixel of ANY element. `body.getBoundingClientRect()`
    // under-reports because a Mermaid <svg> overflows its body box (device-verified: body 264 vs svg
    // bottom 272), which clipped the last node; the per-element max bottom (+ a hair) captures it all.
    private const val HEIGHT_JS =
        "(function(){try{var e=document.body.getElementsByTagName('*');" +
            "var max=document.body.getBoundingClientRect().bottom;" +
            "for(var i=0;i<e.length;i++){var b=e[i].getBoundingClientRect().bottom;if(b>max)max=b;}" +
            "return Math.ceil(max)+4;}catch(e){return -1;}})()"

    suspend fun capture(
        context: Context,
        markdown: String,
        theme: RichTheme,
    ): File? {
        val bitmap =
            withContext(Dispatchers.Main) {
                val activity = context as? Activity ?: return@withContext null
                val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@withContext null
                val widthPx = activity.resources.displayMetrics.widthPixels
                val density = activity.resources.displayMetrics.density
                val html =
                    RichHtml.answerHtml(
                        markdown = markdown,
                        textColor = theme.textColor,
                        citationColor = theme.citationColor,
                        codeBackground = theme.codeBackground,
                        mutedColor = theme.mutedColor,
                        dark = theme.dark,
                        crossRefColor = theme.crossRefColor,
                    )
                withTimeoutOrNull(MAX_WAIT_MS + 4_000L) {
                    renderToBitmap(activity, root, html, theme.backgroundArgb, widthPx, density)
                }
            } ?: return null
        return withContext(Dispatchers.IO) { writePng(context, bitmap) }
    }

    /**
     * Attaches a faintly-visible capture WebView (occluded by the modal sheet, so the user never sees
     * it — but **not** `alpha = 0`, which frame-culls the view and starves the async render), host-polls
     * for render-complete, snapshots, detaches. The host owns the timing (coroutine `delay`) + reads
     * readiness via host-initiated `evaluateJavascript`, so nothing depends on the renderer's own
     * (throttled, off-screen) page timers / `requestAnimationFrame`.
     */
    private suspend fun renderToBitmap(
        activity: Activity,
        root: ViewGroup,
        html: String,
        backgroundArgb: Int,
        widthPx: Int,
        density: Float,
    ): Bitmap? {
        val webView = WebView(activity)
        webView.applyRichSandbox()
        webView.setBackgroundColor(backgroundArgb)
        // Software layer: a HW-accelerated WebView draws blank to a software Canvas.
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        webView.alpha = 0.004f // imperceptible, but non-zero so the compositor keeps producing frames
        webView.webViewClient =
            object : WebViewClient() {
                // Block every navigation (the on-screen arxiver://height self-size pings included);
                // the capture path measures height itself. Asset sub-resources don't route here.
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = true
            }
        val displayH = activity.resources.displayMetrics.heightPixels
        root.addView(webView, ViewGroup.LayoutParams(widthPx, displayH))
        return try {
            webView.loadDataWithBaseURL("file:///android_asset/katex/", html, "text/html", "utf-8", null)
            var waited = 0L
            while (waited < MAX_WAIT_MS) {
                delay(POLL_INTERVAL_MS)
                waited += POLL_INTERVAL_MS
                if ((webView.evalInt(READY_JS) ?: -1) > 0) break // fonts + every Mermaid <svg> present
            }
            delay(FINAL_FLUSH_MS) // settle tick so the final layout/paint is flushed before we measure + draw
            val cssHeight = webView.evalInt(HEIGHT_JS) ?: return null
            if (cssHeight <= 0) return null
            val h = RichRenderSignal.deviceHeightPx(cssHeight, density)
            runCatching { snapshot(webView, widthPx, h, backgroundArgb) }.getOrNull()
        } finally {
            root.removeView(webView)
            webView.destroy()
        }
    }

    /** Bridges a host-initiated `evaluateJavascript` to a suspend call returning the numeric result (or null). */
    private suspend fun WebView.evalInt(js: String): Int? =
        suspendCancellableCoroutine { cont ->
            evaluateJavascript(js) { result ->
                if (cont.isActive) cont.resume(result?.trim()?.removeSurrounding("\"")?.toDoubleOrNull()?.toInt())
            }
        }

    /** Measures + lays out the (attached) WebView at the final size and draws it onto an opaque bitmap. */
    private fun snapshot(
        view: View,
        widthPx: Int,
        heightPx: Int,
        backgroundArgb: Int,
    ): Bitmap {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, widthPx, heightPx)
        val bitmap = createBitmap(widthPx, heightPx)
        Canvas(bitmap).apply {
            drawColor(backgroundArgb) // opaque fill so the PNG isn't see-through
            view.draw(this)
        }
        return bitmap
    }

    private fun writePng(
        context: Context,
        bitmap: Bitmap,
    ): File? =
        runCatching {
            val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            val file = File(dir, "answer_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file
        }.getOrNull()
}
