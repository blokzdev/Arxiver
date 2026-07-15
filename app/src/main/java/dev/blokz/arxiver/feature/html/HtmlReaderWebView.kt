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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import dev.blokz.arxiver.core.ai.ReaderPosition
import dev.blokz.arxiver.ui.markdown.applyRichSandbox
import kotlin.math.roundToInt

/**
 * The WebView `textZoom` (percent) that honours the user's system font-scale (HR-FMT.5). Clamped to a sane
 * band so an extreme accessibility scale can't render the reader unusable. Pure so it's unit-testable.
 */
internal fun readerTextZoom(fontScale: Float): Int = (fontScale * 100f).roundToInt().coerceIn(50, 300)

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

    /** Fires once per generation when the document becomes visible — PH.7 re-issues find here. */
    var onRevealed: (() -> Unit)? = null
    private var revealedGeneration = -1

    /** Generation the last findAllAsync was issued for; stale FindListener callbacks are dropped. */
    private var findIssuedGeneration = -1

    /** True iff the current document owns the last-issued find (gates FindListener dispatches). */
    val findStampCurrent: Boolean get() = findIssuedGeneration == generation

    fun findAll(query: String) {
        findIssuedGeneration = generation
        webView?.findAllAsync(query)
    }

    fun findNext(forward: Boolean) {
        webView?.findNext(forward)
    }

    fun clearFind() {
        findIssuedGeneration = -1
        webView?.clearMatches()
    }

    /**
     * Read the current text selection (PH.7) — generation-stamped like [probe], but a stale result
     * is DELIVERED as null (never swallowed) so the caller can always finish the ActionMode; text
     * from a dead document can never seed the new document's sheet.
     */
    fun readSelection(onResult: (String?) -> Unit) {
        val wv = webView ?: return onResult(null)
        val stamped = generation
        wv.evaluateJavascript(ReaderScrollJs.selection()) { raw ->
            onResult(if (generation == stamped) ReaderScrollJs.parseSelectionResult(raw) else null)
        }
    }

    /** The single reveal funnel — all three reveal paths route here; fires [onRevealed] once per load. */
    private fun reveal(wv: WebView) {
        wv.alpha = 1f
        if (revealedGeneration == generation) return
        revealedGeneration = generation
        onRevealed?.invoke()
    }

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
            reveal(wv)
            return
        }
        tickedSinceRestore = false
        wv.evaluateJavascript(ReaderScrollJs.restore(target!!)) {
            if (generation != stamped) return@evaluateJavascript
            restorePending = false
            reveal(wv)
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
            // Failsafe: a stuck restore must never leave the reader blank. Routed through the
            // reveal() funnel — once-per-generation semantics prevent a double find re-issue
            // when the failsafe fires after a successful restore already revealed.
            wv.postDelayed({ if (generation == stamped) reveal(wv) }, REVEAL_FAILSAFE_MS)
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
    askSelectionLabel: String,
    onPaperClick: (String) -> Unit,
    onExternalUrl: (String) -> Unit,
    onAnchorTap: (String) -> Unit,
    onScrollTick: () -> Unit,
    onPageReady: () -> Unit,
    onFindResult: (Int, Int, Boolean) -> Unit,
    onAskSelection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onPaper by rememberUpdatedState(onPaperClick)
    val onExternal by rememberUpdatedState(onExternalUrl)
    val onAnchor by rememberUpdatedState(onAnchorTap)
    val onTick by rememberUpdatedState(onScrollTick)
    val onReady by rememberUpdatedState(onPageReady)
    val onFind by rememberUpdatedState(onFindResult)
    val onAsk by rememberUpdatedState(onAskSelection)

    // System font-scale honoured (HR-FMT.5). Read here (Compose scope) and apply ONCE at factory time — never
    // in `update`, since a live textZoom change relayouts the WebView and would bypass the PH.6 restore funnel
    // (and it's the reader's own setting, not the shared applyRichSandbox's).
    val textZoom = readerTextZoom(LocalDensity.current.fontScale)

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            ReaderWebView(context).apply {
                applyRichSandbox()
                settings.blockNetworkImage = true // belt-and-suspenders over blockNetworkLoads
                settings.textZoom = textZoom
                controller.webView = this
                askLabel = askSelectionLabel
                // Read-then-finish (PH.7): mode.finish() clears the selection on the renderer with
                // no ordering guarantee vs evaluateJavascript — so read FIRST, finish in the read
                // callback (UI thread), then hand off. Stale/blank → quiet no-op, mode still closed.
                // (`this.` is load-bearing: the composable's parameter shadows the receiver property.)
                this.onAskSelection = { mode ->
                    controller.readSelection { text ->
                        mode.finish()
                        text?.takeIf { it.isNotBlank() }?.let { onAsk(it) }
                    }
                }
                // Stale-generation FindListener dispatches (a reload mid-count) are dropped — the
                // same stamping discipline as the position probe.
                setFindListener { active, total, done ->
                    if (controller.findStampCurrent) onFind(active, total, done)
                }
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
            controller.clearFind()
            controller.webView = null
            it.destroy()
        },
    )
}
