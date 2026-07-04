package dev.blokz.arxiver.feature.html

import android.app.Application
import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.ui.markdown.applyRichSandbox
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PH.7: the selection-ActionMode wrapper. Pins (a) exactly ONE Ask item regardless of repeated
 * prepare passes, (b) delegation of create/click to the inner (Chromium) callback, (c) the
 * no-synchronous-finish + double-tap-guard contract, and (d) that the offline sandbox applies to
 * the subclass unchanged (applyRichSandbox is an extension on the WebView supertype).
 */
@RunWith(RobolectricTestRunner::class)
class ReaderWebViewTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun newMenu(): Menu = PopupMenu(context, View(context)).menu

    /** Minimal concrete ActionMode: records finish() calls, exposes a menu. */
    private inner class FakeActionMode : ActionMode() {
        var finishCount = 0
        private val menu = newMenu()

        override fun setTitle(title: CharSequence?) = Unit

        override fun setTitle(resId: Int) = Unit

        override fun setSubtitle(subtitle: CharSequence?) = Unit

        override fun setSubtitle(resId: Int) = Unit

        override fun setCustomView(view: View?) = Unit

        override fun invalidate() = Unit

        override fun finish() {
            finishCount++
        }

        override fun getMenu(): Menu = menu

        override fun getTitle(): CharSequence? = null

        override fun getSubtitle(): CharSequence? = null

        override fun getCustomView(): View? = null

        override fun getMenuInflater(): MenuInflater = MenuInflater(context)
    }

    private class RecordingCallback : ActionMode.Callback {
        val clickedIds = mutableListOf<Int>()

        override fun onCreateActionMode(
            mode: ActionMode,
            menu: Menu,
        ): Boolean {
            menu.add(0, SYSTEM_COPY_ID, 0, "Copy")
            return true
        }

        override fun onPrepareActionMode(
            mode: ActionMode,
            menu: Menu,
        ): Boolean = false

        override fun onActionItemClicked(
            mode: ActionMode,
            item: MenuItem,
        ): Boolean {
            clickedIds += item.itemId
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = Unit

        companion object {
            const val SYSTEM_COPY_ID = 7
        }
    }

    @Test
    fun `exactly one Ask item survives repeated prepare passes, alongside the system items`() {
        val webView = ReaderWebView(context).apply { askLabel = "Ask about this" }
        val wrapped = webView.wrapForTest(RecordingCallback())
        val mode = FakeActionMode()
        val menu = newMenu()

        assertTrue(wrapped.onCreateActionMode(mode, menu))
        wrapped.onPrepareActionMode(mode, menu)
        wrapped.onPrepareActionMode(mode, menu) // repeated prepare must stay idempotent

        val askItems = (0 until menu.size()).map { menu.getItem(it) }.filter { it.itemId == ReaderWebView.ASK_ITEM_ID }
        assertEquals(1, askItems.size, "exactly one Ask item")
        assertEquals("Ask about this", askItems.single().title.toString())
        assertNotNull(menu.findItem(RecordingCallback.SYSTEM_COPY_ID), "system items survive")
    }

    @Test
    fun `ask click fires once (double-tap guarded), never finishes synchronously, never reaches Chromium`() {
        val webView = ReaderWebView(context).apply { askLabel = "Ask" }
        var askInvocations = 0
        webView.onAskSelection = { askInvocations++ }
        val inner = RecordingCallback()
        val wrapped = webView.wrapForTest(inner)
        val mode = FakeActionMode()
        val menu = newMenu()
        wrapped.onCreateActionMode(mode, menu)
        wrapped.onPrepareActionMode(mode, menu)

        val askItem = menu.findItem(ReaderWebView.ASK_ITEM_ID)!!
        assertTrue(wrapped.onActionItemClicked(mode, askItem))
        assertTrue(wrapped.onActionItemClicked(mode, askItem))

        assertEquals(1, askInvocations, "double-tap fires the read exactly once")
        assertEquals(0, mode.finishCount, "the wrapper never finishes the mode synchronously")
        assertTrue(inner.clickedIds.isEmpty(), "the Ask id never reaches the inner callback")

        // Foreign ids still delegate; destroy resets the double-tap guard.
        wrapped.onActionItemClicked(mode, menu.findItem(RecordingCallback.SYSTEM_COPY_ID)!!)
        assertEquals(listOf(RecordingCallback.SYSTEM_COPY_ID), inner.clickedIds)
        wrapped.onDestroyActionMode(mode)
        wrapped.onActionItemClicked(mode, askItem)
        assertEquals(2, askInvocations, "a new mode after destroy can ask again")
    }

    @Test
    fun `the offline sandbox applies to the subclass unchanged`() {
        val webView = ReaderWebView(context)
        webView.applyRichSandbox()
        assertTrue(webView.settings.blockNetworkLoads)
        assertFalse(webView.settings.allowContentAccess)
    }
}
