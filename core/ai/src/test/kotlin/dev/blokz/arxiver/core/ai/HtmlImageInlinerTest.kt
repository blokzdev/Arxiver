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
