package dev.blokz.arxiver.feature.html

import dev.blokz.arxiver.core.ai.ReaderPosition
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PH.6: the injected-JS builders must neutralize hostile (document-derived) anchor ids, and the
 * probe's untrusted return must parse defensively. Robolectric for org.json.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderScrollJsTest {
    @Test
    fun `jump quotes hostile ids into inert JS string literals`() {
        val hostile = """S1"');alert(1);//\ </script>"""
        val js = ReaderScrollJs.jump(hostile, smooth = false)
        assertFalse(js.contains("alert(1);//"), "raw payload must not survive unescaped")
        assertTrue(js.contains("""\"""") || js.contains("""\\"""), "quoting must escape the payload")
        assertTrue(js.contains("scrollIntoView"))
    }

    @Test
    fun `restore with an anchor jumps then offsets, without an anchor scrolls by fraction`() {
        val withAnchor = ReaderScrollJs.restore(ReaderPosition("S2.SS1", 340, 0.4f))
        assertTrue(withAnchor.contains("scrollIntoView"))
        assertTrue(withAnchor.contains("scrollBy(0,340)"))
        assertTrue(withAnchor.contains("'auto'"), "restores are always instant")

        val fractionOnly = ReaderScrollJs.restore(ReaderPosition(null, 0, 0.5f))
        assertTrue(fractionOnly.contains("scrollTo"))
        assertFalse(fractionOnly.contains("scrollIntoView"))
    }

    @Test
    fun `restore clamps a negative offset and an out-of-range fraction`() {
        assertTrue(ReaderScrollJs.restore(ReaderPosition("S1", -5, 0f)).contains("scrollBy(0,0)"))
        assertTrue(ReaderScrollJs.restore(ReaderPosition(null, 0, 7f)).contains("*1.0"))
    }

    @Test
    fun `probe embeds ids as a JSON array — hostile ids stay data`() {
        val js = ReaderScrollJs.probe(listOf("S1", """S2"];alert(1);["x"""))
        assertTrue(js.contains("getBoundingClientRect"))
        assertFalse(js.contains("""S2"];alert"""), "hostile id must be JSON-escaped")
    }

    @Test
    fun `parseProbeResult round-trips a valid payload`() {
        // evaluateJavascript returns the JSON-encoded string form.
        val raw = "\"{\\\"a\\\":\\\"S2.SS1\\\",\\\"o\\\":340,\\\"f\\\":0.41}\""
        assertEquals(ReaderPosition("S2.SS1", 340, 0.41f), ReaderScrollJs.parseProbeResult(raw))
    }

    @Test
    fun `parseProbeResult handles null anchor, garbage, and clamps`() {
        val nullAnchor = "\"{\\\"a\\\":null,\\\"o\\\":-3,\\\"f\\\":2.5}\""
        assertEquals(ReaderPosition(null, 0, 1f), ReaderScrollJs.parseProbeResult(nullAnchor))

        assertNull(ReaderScrollJs.parseProbeResult(null))
        assertNull(ReaderScrollJs.parseProbeResult("null"))
        assertNull(ReaderScrollJs.parseProbeResult("not json at all"))
        assertNull(ReaderScrollJs.parseProbeResult("\"not an object\""))
    }
}
