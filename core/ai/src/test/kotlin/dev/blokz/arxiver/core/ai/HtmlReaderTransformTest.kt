package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.model.ArxivId
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Goldens for the P-HTML [HtmlReaderTransform] (PH.3). Real native+ar5iv fixtures (sanitized first,
 * exactly like production) prove benign LaTeXML survives + every link internalizes + zero external
 * host; focused inline cases prove the dual citation rewrite, the boundary-anchored id match, fidelity
 * thresholds, and the image placeholder. Structural assertions (re-parse + inspect) throughout.
 */
class HtmlReaderTransformTest {
    private fun res(name: String): String =
        javaClass.getResourceAsStream("/phtml/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("missing fixture /phtml/$name")

    private fun reparse(html: String) = Jsoup.parse(html, "", Parser.htmlParser())

    /** Production path: sanitize → transform. */
    private fun prepare(
        fixture: String,
        id: String,
        source: HtmlSource,
        version: Int = 1,
    ): ReaderDocument {
        val sanitized = HtmlSanitizer.sanitize(res(fixture)) ?: error("sanitize null for $fixture")
        return HtmlReaderTransform.transform(sanitized, ArxivId(id), source, version)
            ?: error("transform null for $fixture")
    }

    private fun assertNoExternalHost(bodyHtml: String) {
        val d = reparse(bodyHtml)
        d.allElements.forEach { el ->
            for (a in listOf("href", "src", "xlink:href")) {
                val v = el.attr(a).trim().lowercase()
                assertFalse(
                    v.startsWith("http://") || v.startsWith("https://") || v.startsWith("//"),
                    "external $a on <${el.normalName()}>: $v",
                )
            }
        }
    }

    // --- real fixtures -----------------------------------------------------------------------------

    @Test
    fun `native clean transforms OK with internalized links and no external host`() {
        val doc = prepare("native-clean-2412.19437.html", "2412.19437", HtmlSource.NATIVE)
        assertEquals(Fidelity.OK, doc.fidelity.fidelity, doc.fidelity.toString())
        assertNoExternalHost(doc.bodyHtml)
        val d = reparse(doc.bodyHtml)
        assertTrue(d.select("a[href^=arxiver://]").isNotEmpty(), "links internalized")
        // the site chrome (navbar/TOC siblings of the article) is not part of the extracted content
        assertTrue(d.select("nav.ltx_page_navbar, nav.ltx_TOC").isEmpty(), "site chrome excluded")
        assertTrue(doc.anchors.any { it.type == AnchorType.BIBLIOGRAPHY }, "bib anchors extracted")
    }

    @Test
    fun `ar5iv clean transforms OK with bare cites and the self-link internalized`() {
        val doc = prepare("ar5iv-clean-1706.03762.html", "1706.03762", HtmlSource.AR5IV)
        assertEquals(Fidelity.OK, doc.fidelity.fidelity)
        assertNoExternalHost(doc.bodyHtml) // ar5iv's absolute self-link must become arxiver://, not survive
        val d = reparse(doc.bodyHtml)
        assertTrue(d.select("a[href^='arxiver://anchor/']").isNotEmpty(), "bare #bib/#S cites → anchor uris")
        assertTrue(d.select("math").isNotEmpty())
        assertTrue(doc.anchors.any { it.type == AnchorType.BIBLIOGRAPHY }, "bib anchors extracted")
    }

    @Test
    fun `native degraded is flagged DEGRADED but still produces a document`() {
        val doc = prepare("native-degraded-2510.04905.html", "2510.04905", HtmlSource.NATIVE, version = 3)
        assertEquals(Fidelity.DEGRADED, doc.fidelity.fidelity)
        assertNotNull(doc.fidelity.reason)
        assertTrue(doc.fidelity.missingCitationCount > 0)
        assertNoExternalHost(doc.bodyHtml)
        // PH.5: same-origin figures become opaque tokens (no src) + manifest entries resolved against the
        // exact native page URL (version-prefixed src, no-trailing-slash base → no doubled version dir).
        val d = reparse(doc.bodyHtml)
        val tokens = d.select("img[data-img-key]")
        assertTrue(tokens.isNotEmpty(), "images tokenized")
        assertTrue(tokens.none { it.hasAttr("src") }, "tokens carry no src")
        assertEquals(tokens.size, doc.images.size, "one manifest entry per kept figure")
        assertTrue(
            doc.images.all { it.fetchUrl.startsWith("https://arxiv.org/html/2510.04905v3/") },
            doc.images.map { it.fetchUrl }.toString(),
        )
        assertTrue(doc.images.any { it.fetchUrl == "https://arxiv.org/html/2510.04905v3/x1.png" })
    }

    // --- focused inline cases ----------------------------------------------------------------------

    private fun t(
        body: String,
        id: String = "2412.19437",
        source: HtmlSource = HtmlSource.NATIVE,
        version: Int = 1,
    ) = HtmlReaderTransform.transform(body, ArxivId(id), source, version)!!

    @Test
    fun `bare fragment and bib cites become anchor uris`() {
        val d = reparse(t("""<section id="S1"><a href="#S2">x</a><a href="#bib.bib3">[1]</a></section>""").bodyHtml)
        val hrefs = d.select("a[href]").map { it.attr("href") }
        assertTrue(hrefs.any { it == "arxiver://anchor/2412.19437?frag=S2" }, hrefs.toString())
        assertTrue(hrefs.any { it == "arxiver://anchor/2412.19437?frag=bib.bib3" }, hrefs.toString())
    }

    @Test
    fun `a native absolute same-paper cite internalizes to an anchor`() {
        val d =
            reparse(
                t("""<p><a href="https://arxiv.org/html/2412.19437v2#bib.bib3">[1]</a></p>""").bodyHtml,
            )
        assertEquals(
            "arxiver://anchor/2412.19437?frag=bib.bib3",
            d.selectFirst("a[href]")!!.attr("href"),
        )
    }

    @Test
    fun `the same-paper match is boundary-anchored, not a bare prefix`() {
        // 2412.194370 starts with 2412.19437 but is a DIFFERENT paper → must be paper, not anchor.
        val d = reparse(t("""<p><a href="https://arxiv.org/html/2412.194370v1">other</a></p>""").bodyHtml)
        assertEquals("arxiver://paper/2412.194370", d.selectFirst("a[href]")!!.attr("href"))
    }

    @Test
    fun `a different paper's html link opens that paper`() {
        val d = reparse(t("""<p><a href="https://ar5iv.labs.arxiv.org/html/1706.03762#S2">attn</a></p>""").bodyHtml)
        assertEquals("arxiver://paper/1706.03762", d.selectFirst("a[href]")!!.attr("href"))
    }

    @Test
    fun `an external link is wrapped and marked, never left external`() {
        val d = reparse(t("""<p><a href="https://github.com/foo/bar">code</a></p>""").bodyHtml)
        val a = d.selectFirst("a[href]")!!
        assertTrue(a.attr("href").startsWith("arxiver://external?url="), a.attr("href"))
        assertTrue(
            a.attr("href").contains("github.com"),
            "the original url is url-encoded in the query: ${a.attr("href")}",
        )
        assertEquals("true", a.attr("data-external"))
        assertTrue(a.hasClass("ltx_external"))
    }

    @Test
    fun `a dangerous scheme that slipped sanitization is dropped`() {
        val d = reparse(t("""<p><a href="javascript:alert(1)">x</a></p>""").bodyHtml)
        assertFalse(d.selectFirst("a")!!.hasAttr("href"), "javascript: href dropped")
        assertNoExternalHost(t("""<p><a href="javascript:alert(1)">x</a></p>""").bodyHtml)
    }

    @Test
    fun `a native version-prefixed figure becomes a token resolved against the no-slash page url`() {
        // Real native src is version-prefixed; resolving it against the no-trailing-slash page URL must
        // NOT double the version dir (the load-bearing PH.5 resolution fix).
        val doc =
            t(
                """<figure id="S1.F1"><img src="2412.19437v1/x1.png" width="830" height="485" alt="Fig">""" +
                    """<figcaption>Cap</figcaption></figure>""",
                version = 1,
            )
        val d = reparse(doc.bodyHtml)
        val img = d.selectFirst("img[data-img-key]")!!
        assertFalse(img.hasAttr("src"), "token carries no src")
        assertEquals("830", img.attr("width")) // dimensions + alt kept for CLS + a11y
        assertEquals("Fig", img.attr("alt"))
        assertEquals(1, doc.images.size)
        assertEquals("https://arxiv.org/html/2412.19437v1/x1.png", doc.images.first().fetchUrl)
        assertEquals(img.attr("data-img-key"), doc.images.first().localKey)
        assertTrue(d.select("figcaption").text().contains("Cap"))
    }

    @Test
    fun `an ar5iv absolute-path figure resolves against the ar5iv origin`() {
        val doc =
            t(
                """<figure><img src="/html/1706.03762/assets/x1.png"></figure>""",
                id = "1706.03762",
                source = HtmlSource.AR5IV,
            )
        assertEquals(
            "https://ar5iv.labs.arxiv.org/html/1706.03762/assets/x1.png",
            doc.images.single().fetchUrl,
        )
    }

    @Test
    fun `a data image src is kept inline and never enters the manifest`() {
        val doc = t("""<figure><img src="data:image/png;base64,AAAA"></figure>""")
        val d = reparse(doc.bodyHtml)
        assertEquals("data:image/png;base64,AAAA", d.selectFirst("img")!!.attr("src"))
        assertTrue(doc.images.isEmpty())
    }

    @Test
    fun `an absolute external img is rejected to a placeholder and never fetched`() {
        // The transform's own host allowlist (defence-in-depth; the sanitizer also strips external srcs).
        val doc = t("""<figure><img src="https://evil.example/track.gif"></figure>""")
        val d = reparse(doc.bodyHtml)
        assertTrue(d.select("img").isEmpty(), "external img not kept")
        assertTrue(d.select(".ltx_placeholder").isNotEmpty())
        assertTrue(doc.images.isEmpty())
        assertNoExternalHost(doc.bodyHtml)
    }

    @Test
    fun `identical figure srcs dedup to one manifest entry`() {
        val doc = t("""<p><img src="2412.19437v1/x1.png"><img src="2412.19437v1/x1.png"></p>""")
        assertEquals(1, doc.images.size, "deduped by resolved url")
        val keys = reparse(doc.bodyHtml).select("img[data-img-key]").map { it.attr("data-img-key") }.toSet()
        assertEquals(1, keys.size, "both tokens share the key")
    }

    @Test
    fun `figures past the count cap fall back to placeholders`() {
        val body =
            buildString {
                append("<div>")
                repeat(HtmlReaderTransform.IMAGE_COUNT_CAP + 5) { append("""<img src="2412.19437v1/x$it.png">""") }
                append("</div>")
            }
        val doc = t(body)
        assertEquals(HtmlReaderTransform.IMAGE_COUNT_CAP, doc.images.size, "manifest capped")
        val d = reparse(doc.bodyHtml)
        assertEquals(HtmlReaderTransform.IMAGE_COUNT_CAP, d.select("img[data-img-key]").size)
        assertEquals(5, d.select(".ltx_placeholder").size, "the tail is placeholders")
        assertTrue(HtmlReaderTransform.assertNoExternalHost(doc.bodyHtml), "tokens are not external")
    }

    @Test
    fun `without an article the body fallback strips site chrome but keeps content`() {
        val doc =
            t(
                """<nav class="ltx_page_navbar"><a href="https://arxiv.org/x">nav</a></nav>""" +
                    """<nav class="ltx_TOC"><a href="#S1">toc</a></nav>""" +
                    """<section id="S1"><p>Real content.</p></section>""",
            )
        val d = reparse(doc.bodyHtml)
        assertTrue(d.select("nav").isEmpty(), "navbar + TOC stripped on the body fallback")
        assertTrue(d.select("section#S1").isNotEmpty() && d.text().contains("Real content"), "content kept")
    }

    @Test
    fun `MathML is kept verbatim`() {
        val d = reparse(t("""<p><math display="inline"><mrow><mi>x</mi></mrow></math></p>""").bodyHtml)
        assertTrue(d.select("math mi").isNotEmpty())
    }

    @Test
    fun `fidelity is OK with a bibliography and DEGRADED when cites have no resolvable bib`() {
        val ok =
            t(
                """<p><a class="ltx_ref" href="#bib.bib1">[1]</a></p>""" +
                    """<ul class="ltx_biblist"><li id="bib.bib1">ref</li></ul>""",
            )
        assertEquals(Fidelity.OK, ok.fidelity.fidelity)

        // 3 bib-targeting cites, zero resolvable bib entries → DEGRADED (the no-bib rule).
        val degraded =
            t(
                """<p>""" +
                    """<a class="ltx_ref" href="#bib.bib1">[1]</a><a class="ltx_ref" href="#bib.bib2">[2]</a>""" +
                    """<a class="ltx_ref" href="#bib.bib3">[3]</a>""" +
                    """</p>""",
            )
        assertEquals(Fidelity.DEGRADED, degraded.fidelity.fidelity)
        assertEquals(0, degraded.fidelity.resolvableBibCount)
    }

    @Test
    fun `high missing-citation density flags DEGRADED`() {
        val body =
            buildString {
                append("<p><a class=\"ltx_ref\" href=\"#bib.bib1\">[1]</a>")
                repeat(3) { append("<span class=\"ltx_missing_citation\">?</span>") } // 3 missing / (1+3) = 0.75
                append("</p><ul class=\"ltx_biblist\"><li id=\"bib.bib1\">r</li></ul>")
            }
        assertEquals(Fidelity.DEGRADED, t(body).fidelity.fidelity)
    }

    @Test
    fun `blank or content-less input returns null`() {
        assertNull(HtmlReaderTransform.transform("", ArxivId("2412.19437"), HtmlSource.NATIVE, 1))
        assertNull(HtmlReaderTransform.transform("   ", ArxivId("2412.19437"), HtmlSource.NATIVE, 1))
    }
}
