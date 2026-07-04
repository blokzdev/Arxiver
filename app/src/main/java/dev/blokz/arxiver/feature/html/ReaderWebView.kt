package dev.blokz.arxiver.feature.html

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView

/**
 * The reader's WebView subclass (P-HTML PH.7): adds an **"Ask about this"** item to the text-selection
 * floating ActionMode. Native ActionMode, NOT a page-JS bridge — the selection text is read host-side
 * afterwards via the sanctioned `evaluateJavascript` channel (SPEC-P-HTML §12/§13).
 *
 * Only the 2-arg [startActionMode] is overridden — the 1-arg overload delegates into it in AOSP
 * `View`, so overriding both would double-wrap (pinned by test). Degradation-first: if any platform
 * detail shifts (Chromium menu rebuilds, floating-toolbar anchoring), the wrapper fails safe to the
 * stock menu — system Copy still works and the reader's toolbar Ask icon is the guaranteed path.
 */
internal class ReaderWebView(context: Context) : WebView(context) {
    /** Invoked when the user taps "Ask about this"; the receiver reads the selection then finishes [ActionMode]. */
    var onAskSelection: ((ActionMode) -> Unit)? = null

    /** Localized menu title (strings.xml — a plain View class can't resolve compose resources). */
    var askLabel: String = ""

    override fun startActionMode(
        callback: ActionMode.Callback,
        type: Int,
    ): ActionMode? = super.startActionMode(AskSelectionCallback(callback), type)

    /**
     * Wraps Chromium's selection callback. MUST be a [ActionMode.Callback2]: Chromium's own callback
     * anchors the floating toolbar to the live selection rect via `onGetContentRect` — a plain
     * Callback silently degrades to whole-view positioning.
     */
    private inner class AskSelectionCallback(
        private val inner: ActionMode.Callback,
    ) : ActionMode.Callback2() {
        private var askInFlight = false

        override fun onCreateActionMode(
            mode: ActionMode,
            menu: Menu,
        ): Boolean =
            // Delegate first and return ITS result — returning false where Chromium returned true
            // makes it clear the selection instantly.
            inner.onCreateActionMode(mode, menu)

        override fun onPrepareActionMode(
            mode: ActionMode,
            menu: Menu,
        ): Boolean {
            val result = inner.onPrepareActionMode(mode, menu)
            // Chromium rebuilds its menu here but clears only its OWN groups — our app-owned group
            // survives; the findItem guard keeps repeated prepare passes idempotent. Ordered AFTER
            // the system items (never displace Copy's muscle-memory slot).
            if (menu.findItem(ASK_ITEM_ID) == null) {
                menu.add(ASK_GROUP_ID, ASK_ITEM_ID, ORDER_AFTER_SYSTEM, askLabel).apply {
                    contentDescription = askLabel // API 26 == minSdk
                }
            }
            return result
        }

        override fun onActionItemClicked(
            mode: ActionMode,
            item: MenuItem,
        ): Boolean {
            if (item.itemId != ASK_ITEM_ID) return inner.onActionItemClicked(mode, item)
            if (!askInFlight) {
                askInFlight = true
                // NEVER mode.finish() synchronously here — finishing clears the selection on the
                // renderer with no ordering guarantee vs the evaluateJavascript read. The receiver
                // reads first, then finishes the mode from the read callback.
                onAskSelection?.invoke(mode)
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            askInFlight = false
            inner.onDestroyActionMode(mode)
        }

        override fun onGetContentRect(
            mode: ActionMode,
            view: View,
            outRect: Rect,
        ) {
            (inner as? ActionMode.Callback2)?.onGetContentRect(mode, view, outRect)
                ?: super.onGetContentRect(mode, view, outRect)
        }
    }

    /** Test seam: the exact wrapper [startActionMode] installs (Robolectric can't observe the real mode). */
    internal fun wrapForTest(inner: ActionMode.Callback): ActionMode.Callback2 = AskSelectionCallback(inner)

    internal companion object {
        const val ASK_GROUP_ID = 0x4152 // app-owned group; Chromium clears only its own groups
        const val ASK_ITEM_ID = 0x4153
        const val ORDER_AFTER_SYSTEM = 100
    }
}
