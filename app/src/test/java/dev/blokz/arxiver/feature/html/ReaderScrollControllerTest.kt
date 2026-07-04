package dev.blokz.arxiver.feature.html

import android.app.Application
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PH.7 controller contracts: find-generation stamping, readSelection's deliver-null-never-swallow
 * rule, and the once-per-generation reveal funnel that sequences the find re-issue AFTER the PH.6
 * restore on every reload path (including the conceal failsafe).
 */
@RunWith(RobolectricTestRunner::class)
class ReaderScrollControllerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `findAll stamps the current generation and a reload makes the stamp stale`() {
        val controller = ReaderScrollController()
        controller.webView = WebView(context)

        controller.findAll("attention")
        assertTrue(controller.findStampCurrent)

        controller.beginLoad(conceal = false) // generation++
        assertFalse(controller.findStampCurrent, "a FindListener callback from the old document must be droppable")

        controller.findAll("attention") // re-issue for the new document
        assertTrue(controller.findStampCurrent)

        controller.clearFind()
        assertFalse(controller.findStampCurrent)
    }

    @Test
    fun `readSelection delivers null - never swallows - when no webview is attached`() {
        val controller = ReaderScrollController()
        var delivered = false
        var result: String? = "sentinel"

        controller.readSelection {
            delivered = true
            result = it
        }

        assertTrue(delivered, "the caller must always be able to finish the ActionMode")
        assertNull(result)
    }

    @Test
    fun `onRevealed fires exactly once per generation across the no-target path and the failsafe`() {
        val controller = ReaderScrollController()
        val webView = WebView(context)
        controller.webView = webView
        var revealed = 0
        controller.onRevealed = { revealed++ }

        // Load 1: conceal, then the no-target onPageReady path reveals; the 500ms failsafe fires
        // later in the same generation and must NOT re-fire the hook.
        controller.beginLoad(conceal = true)
        controller.onPageReady(target = null, minFraction = 0.02f, onApplied = {})
        assertEquals(1, revealed)
        assertEquals(1f, webView.alpha)
        shadowOf(context.mainLooper).idle() // run the failsafe postDelayed
        assertEquals(1, revealed, "the failsafe never double-fires the reveal hook")

        // Load 2: a new generation reveals (and re-fires) again.
        controller.beginLoad(conceal = true)
        assertEquals(0f, webView.alpha)
        controller.onPageReady(target = null, minFraction = 0.02f, onApplied = {})
        assertEquals(2, revealed)
    }
}
