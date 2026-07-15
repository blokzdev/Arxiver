package dev.blokz.arxiver.core.ai

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReaderDocWriterTest {
    private val theme =
        ReaderTheme(
            text = "#111111",
            background = "#ffffff",
            link = "#0066cc",
            muted = "#888888",
            mutedText = "#555555",
            codeBackground = "#eeeeee",
        )

    private fun doc(body: String) =
        ReaderDocument(
            bodyHtml = body,
            fidelity = FidelityReport(Fidelity.OK, null, 0, 1, 1),
            anchors = emptyList(),
            source = HtmlSource.NATIVE,
        )

    @Test
    fun `writes a self-contained document with CSP, one inlined style, theme vars, body, no scripts`() {
        val html = ReaderDocWriter.write(doc("<p id=\"x\">hello world</p>"), theme)
        val d = Jsoup.parse(html)

        val csp = d.selectFirst("meta[http-equiv=Content-Security-Policy]")
        assertNotNull(csp, "CSP meta present")
        assertTrue(csp.attr("content").contains("script-src 'none'"), csp.attr("content"))

        assertEquals(1, d.select("style").size, "exactly one inlined <style>")
        assertTrue(d.select("link[rel=stylesheet]").isEmpty(), "no external stylesheet <link>")

        val style = d.selectFirst("style")!!.data()
        assertTrue(style.contains("--reader-text:#111111"), "theme var injected")
        assertTrue(style.contains("var(--reader-text)"), "reader.css references the var (inlined)")
        assertTrue(style.contains("max-width: 100%"), "PH.5 img sizing rule present in the bundled css")

        assertTrue(d.selectFirst("body")!!.html().contains("hello world"), "body preserved")

        // The reader is a natively-scrolling full-screen page — no self-size script, no scripts at all.
        assertTrue(d.select("script").isEmpty(), "no scripts")
        assertFalse(html.contains("arxiver://height"), "no self-size signal")
    }

    @Test
    fun `muted TEXT and muted BORDER are separate tokens so captions clear contrast`() {
        val style = Jsoup.parse(ReaderDocWriter.write(doc("<p>x</p>"), theme)).selectFirst("style")!!.data()

        // Both tokens are injected...
        assertTrue(style.contains("--reader-muted:#888888"), "border token injected")
        assertTrue(style.contains("--reader-muted-text:#555555"), "muted-text token injected")

        // ...muted TEXT rules reference the readable token, while the data-table BORDER keeps the low-contrast one.
        assertTrue(style.contains("color: var(--reader-muted-text)"), "muted text uses the readable token")
        assertTrue(
            style.contains("border: 1px solid var(--reader-muted)"),
            "table borders keep the low-contrast border token",
        )
    }

    @Test
    fun `inline font-runs are styled and display equations are de-gridlined without touching data tables`() {
        val style = Jsoup.parse(ReaderDocWriter.write(doc("<p>x</p>"), theme)).selectFirst("style")!!.data()

        // HR-FMT.1: inline font-run classes now carry real styling.
        assertTrue(style.contains(".ltx_font_italic"), "italic font-run rule present")
        assertTrue(style.contains(".ltx_font_typewriter"), "typewriter font-run rule present")

        // HR-FMT.2: equation tables lose their inherited gridlines...
        assertTrue(style.contains(".ltx_eqn_table"), "equation-table de-gridline rule present")
        // ...while genuine data tables keep their borders (the blanket rule is unchanged).
        assertTrue(
            style.contains("border: 1px solid var(--reader-muted)"),
            "real data-table border rule intact",
        )
    }

    @Test
    fun `dark mode adds the body class and the matte rule, light mode does not`() {
        val darkHtml = ReaderDocWriter.write(doc("<p>x</p>"), theme.copy(dark = true))
        val darkDoc = Jsoup.parse(darkHtml)
        assertTrue(darkDoc.selectFirst("body")!!.hasClass("reader-dark"), "dark reader tags the body")
        assertTrue(
            darkDoc.selectFirst("style")!!.data().contains(".reader-dark img.reader-matte"),
            "the dark-only matte rule is present",
        )

        val lightDoc = Jsoup.parse(ReaderDocWriter.write(doc("<p>x</p>"), theme))
        assertFalse(lightDoc.selectFirst("body")!!.hasClass("reader-dark"), "light reader does not tag the body")
    }

    @Test
    fun `the written document has no external host`() {
        val html = ReaderDocWriter.write(doc("<p>x</p>"), theme).lowercase()
        assertFalse(html.contains("http://"), "no http")
        assertFalse(html.contains("https://"), "no https")
        assertFalse(html.contains("//use.typekit") || html.contains("cdn.jsdelivr"), "no CDN")
    }
}
