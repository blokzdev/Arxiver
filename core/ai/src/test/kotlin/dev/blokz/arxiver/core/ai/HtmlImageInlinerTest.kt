package dev.blokz.arxiver.core.ai

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Goldens for [HtmlImageInliner] (PH.5): a fetched token becomes a `data:image` URI, a missed token
 * becomes the figcaption placeholder, the output carries no external host, and — the load-bearing
 * invariant — every emitted `data:` form survives a re-sanitize (the sanitizer's `DATA_IMAGE` allowlist).
 */
class HtmlImageInlinerTest {
    private fun reparse(html: String) = Jsoup.parse(html, "", Parser.htmlParser())

    private val tokenBody =
        """<figure><img data-img-key="k1" width="100" alt="a"><figcaption>Cap</figcaption></figure>""" +
            """<figure><img data-img-key="k2"><figcaption>Miss</figcaption></figure>"""

    @Test
    fun `a fetched token becomes a data image uri and a missed token becomes a placeholder`() {
        val out =
            HtmlImageInliner.inline(tokenBody, mapOf("k1" to InlinedImage("png", "AAAA")))
        val d = reparse(out)

        val img = d.selectFirst("img")!!
        assertEquals("data:image/png;base64,AAAA", img.attr("src"))
        assertFalse(img.hasAttr("data-img-key"), "the token attr is removed")
        assertEquals("a", img.attr("alt"), "alt retained")
        assertEquals(1, d.select("img").size, "the missed token is no longer an <img>")
        assertEquals(1, d.select(".ltx_placeholder").size, "the missed token became a placeholder")
        assertTrue(HtmlReaderTransform.assertNoExternalHost(out), "no external host in the inlined body")
    }

    @Test
    fun `a transparent figure is tagged reader-matte, an opaque one is not, and the class survives sanitize`() {
        val body = """<p><img data-img-key="t"></p><p><img data-img-key="o"></p>"""
        val out =
            HtmlImageInliner.inline(
                body,
                mapOf(
                    "t" to InlinedImage("png", "AAAA", transparent = true),
                    "o" to InlinedImage("jpeg", "BBBB", transparent = false),
                ),
            )
        val imgs = reparse(out).select("img")
        assertTrue(imgs[0].hasClass("reader-matte"), "transparent figure tagged for the matte")
        assertFalse(imgs[1].hasClass("reader-matte"), "opaque figure is not boxed")

        // The matte class must survive the cache-hit re-sanitize, or the matte would vanish on reload.
        val sanitized = HtmlSanitizer.sanitize(out) ?: error("sanitize returned null")
        assertTrue(reparse(sanitized).select("img")[0].hasClass("reader-matte"), "class kept through sanitize")
    }

    @Test
    fun `every raster subtype it emits survives a re-sanitize unchanged`() {
        for (sub in listOf("png", "jpeg", "gif", "webp")) {
            val out =
                HtmlImageInliner.inline(
                    """<p><img data-img-key="k"></p>""",
                    mapOf("k" to InlinedImage(sub, "AAAA")),
                )
            val expected = "data:image/$sub;base64,AAAA"
            assertEquals(expected, reparse(out).selectFirst("img")!!.attr("src"))

            val sanitized = HtmlSanitizer.sanitize(out) ?: error("sanitize returned null for $sub")
            assertEquals(
                expected,
                reparse(sanitized).selectFirst("img")?.attr("src"),
                "the sanitizer must keep the data:image/$sub src (DATA_IMAGE allowlist)",
            )
        }
    }
}
