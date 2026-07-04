package dev.blokz.arxiver.feature.html

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.blokz.arxiver.core.ai.ReaderPosition
import dev.blokz.arxiver.ui.markdown.applyRichSandbox

/**
 * The full-screen, natively-scrolling reader WebView (P-HTML PH.4/PH.6). Reuses the shared offline
 * [dev.blokz.arxiver.ui.markdown.applyRichSandbox] (`blockNetworkLoads=true`, no
 * `@JavascriptInterface`) + `blockNetworkImage=true`; base URL stays **null** (PH.5 `data:` inlining).
 *
 * PH.6 lifecycle contract (SPEC-P-HTML §11/§12): the ViewModel owns ALL scroll POLICY (target slot,
 * jump precedence, settle window); this layer is a dumb sink that reports `(generation, scroll tick)`
 * and executes `(generation, command)`. One authority — [ReaderScrollController.generation] — stamps
 * every probe and gates every callback, so a component can never act on a document other than the one
 * loaded. Restore reads the VM's CURRENT target at load-completion time (inside `onPageFinished`),
 * never a value captured at expose time.
 */
@Stable
internal class ReaderScrollController {
    internal var webView: WebView? = null

    /** Incremented immediately before every `loadDataWithBaseURL`; stamps probes + gates callbacks. */
    internal var generation = 0

    /** Set across a load+restore window; scroll ticks are discarded while it stands (the load-reset
     * scroll(≈0) event must never reach the save pipeline). Failure-safe: also cleared at the next
     * load, in onRelease, and by the restore callback. */
    internal var restorePending = false

    /** True once a REAL user scroll arrived after the last restore — skips the delayed re-apply. */
    internal var tickedSinceRestore = false

    /** The user's own TOC/cite tap — the only animated scroll in the reader. */
    fun jumpTo(anchorId: String) {
        webView?.evaluateJavascript(ReaderScrollJs.jump(anchorId, smooth = true), null)
    }

    fun announce(message: String) {
        webView?.announceForAccessibility(message)
    }

    /** Issue a position probe stamped with the current generation; stale results are dropped. */
    fun probe(
        anchorIds: List<String>,
        onResult: (String?) -> Unit,
    ) {
        val wv = webView ?: return
        if (restorePending) return
        val stamped = generation
        wv.evaluateJavascript(ReaderScrollJs.probe(anchorIds)) { raw ->
            if (generation == stamped) onResult(raw)
        }
    }

    /**
     * Load completed: apply [target] (read by the caller from the VM at THIS moment), reveal, and
     * schedule one guarded delayed re-apply (data:-heavy bodies reflow after onPageFinished — the
     * expected case, since the sanitizer strips width/height).
     */
    fun onPageReady(
        target: ReaderPosition?,
        minFraction: Float,
        onApplied: () -> Unit,
    ) {
        val wv = webView ?: return
        val stamped = generation
        val applies = target != null && (target.anchorId != null || target.fraction >= minFraction)
        if (!applies) {
            restorePending = false
            wv.alpha = 1f
            return
        }
        tickedSinceRestore = false
        wv.evaluateJavascript(ReaderScrollJs.restore(target!!)) {
            if (generation != stamped) return@evaluateJavascript
            restorePending = false
            wv.alpha = 1f
            onApplied()
            // One re-apply after layout settles, skipped if the user scrolled in the meantime.
            wv.postDelayed({
                if (generation == stamped && !tickedSinceRestore) {
                    wv.evaluateJavascript(ReaderScrollJs.restore(target), null)
                }
            }, REAPPLY_DELAY_MS)
        }
    }

    /** Called from the update block right before a reload. */
    fun beginLoad(conceal: Boolean) {
        generation++
        restorePending = true
        val wv = webView ?: return
        if (conceal) {
            wv.alpha = 0f
            val stamped = generation
            // Failsafe: a stuck restore must never leave the reader blank.
            wv.postDelayed({ if (generation == stamped) wv.alpha = 1f }, REVEAL_FAILSAFE_MS)
        }
    }

    private companion object {
        const val REAPPLY_DELAY_MS = 300L
        const val REVEAL_FAILSAFE_MS = 500L
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun HtmlReaderWebView(
    html: String,
    controller: ReaderScrollController,
    onPaperClick: (String) -> Unit,
    onExternalUrl: (String) -> Unit,
    onAnchorTap: (String) -> Unit,
    onScrollTick: () -> Unit,
    onPageReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onPaper by rememberUpdatedState(onPaperClick)
    val onExternal by rememberUpdatedState(onExternalUrl)
    val onAnchor by rememberUpdatedState(onAnchorTap)
    val onTick by rememberUpdatedState(onScrollTick)
    val onReady by rememberUpdatedState(onPageReady)

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                applyRichSandbox()
                settings.blockNetworkImage = true // belt-and-suspenders over blockNetworkLoads
                controller.webView = this
                // Ticks are dropped while a load/restore is pending — the load-reset scroll(≈0)
                // event must never demote a saved position to top-of-page.
                setOnScrollChangeListener { _, _, _, _, _ ->
                    if (!controller.restorePending) {
                        controller.tickedSinceRestore = true
                        onTick()
                    }
                }
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val url = request?.url ?: return true
                            if (url.scheme == "arxiver") {
                                when (url.host) {
                                    "anchor" ->
                                        url.getQueryParameter("frag")?.let { frag ->
                                            // Execute in place (the user's own tap animates) AND report
                                            // the jump intent so the VM's arbitration can hold the slot.
                                            view?.evaluateJavascript(ReaderScrollJs.jump(frag, smooth = true), null)
                                            onAnchor(frag)
                                        }
                                    "paper" -> url.lastPathSegment?.let { onPaper(it) }
                                    "external" -> url.getQueryParameter("url")?.let { onExternal(it) }
                                    // anything else → ignore (the reader scrolls natively).
                                }
                            }
                            return true // block every real navigation; asset sub-resources don't route here
                        }

                        override fun onPageFinished(
                            view: WebView?,
                            url: String?,
                        ) = onReady()
                    }
            }
        },
        update = {
            // Reload only on a real content change — added state must never trigger spurious reloads.
            if (it.tag != html) {
                it.tag = html
                controller.beginLoad(conceal = true)
                it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = {
            controller.restorePending = false
            controller.webView = null
            it.destroy()
        },
    )
}
