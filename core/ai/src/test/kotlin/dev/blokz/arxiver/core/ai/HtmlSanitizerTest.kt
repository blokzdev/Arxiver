package dev.blokz.arxiver.core.ai

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adversarial + benign golden corpus for the P-HTML [HtmlSanitizer] (PH.1). Pure JVM. Fixtures under
 * `core/ai/src/test/resources/phtml/` (see PROVENANCE.md): real trimmed native+ar5iv documents +
 * a hand-built `malicious-battery.html`.
 *
 * Security assertions are **structural** — the sanitized output is re-parsed and inspected element by
 * element — so escaped text (e.g. `&lt;img onerror=…&gt;`, which is inert) can never masquerade as a
 * live handler and cause a false pass/fail.
 */
class HtmlSanitizerTest {
    private fun res(name: String): String =
        javaClass.getResourceAsStream("/phtml/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("missing fixture /phtml/$name")

    private fun clean(raw: String): String = HtmlSanitizer.sanitize(raw) ?: error("sanitize returned null")

    private fun reparse(out: String) = Jsoup.parse(out, "", Parser.htmlParser())

    /** The output must contain no executable/loadable element, no on* handler, no dangerous-scheme ref. */
    private fun assertInert(out: String) {
        val d = reparse(out)
        val banned = "script, iframe, object, embed, applet, form, input, button, link, style, meta, base"
        assertTrue(d.select(banned).isEmpty(), "active/blocked element survived:\n$out")
        assertTrue(
            d.select("foreignObject, maction, annotation-xml").isEmpty(),
            "foreignObject / maction / annotation-xml survived:\n$out",
        )
        val onHandler = d.allElements.firstOrNull { el -> el.attributes().any { it.key.lowercase().startsWith("on") } }
        assertNull(onHandler, "on* handler survived on <${onHandler?.normalName()}>")
        d.allElements.forEach { el ->
            for (k in listOf("href", "xlink:href", "src", "srcset", "poster", "data")) {
                val v = el.attr(k).trim().lowercase()
                assertFalse(
                    v.startsWith("javascript:") || v.startsWith("vbscript:") || v.startsWith("data:text/html"),
                    "dangerous scheme on $k of <${el.normalName()}>: $v",
                )
                // resource (auto-loading) attrs must never carry an external host
                if (k != "href") {
                    assertFalse(
                        v.startsWith("http://") || v.startsWith("https://") || v.startsWith("//"),
                        "external resource on $k of <${el.normalName()}>: $v",
                    )
                }
            }
        }
    }

    // --- the hand-built hostile document ---------------------------------------------------------

    @Test
    fun `malicious battery sanitises to inert while benign structure survives`() {
        val out = clean(res("malicious-battery.html"))
        assertInert(out)
        val d = reparse(out)
        // benign LaTeXML structure must survive
        assertTrue(d.select("math").isNotEmpty(), "MathML kept")
        assertTrue(d.select("mrow, msup").isNotEmpty(), "MathML inner kept")
        assertTrue(d.select("figcaption").isNotEmpty(), "figcaption kept")
        assertTrue(d.select("table, .ltx_tabular").isNotEmpty(), "table kept")
        assertTrue(d.select("annotation").isNotEmpty(), "the TeX <annotation> (text alt) is kept")
        // benign camelCase inline SVG survives via in-tree delegation to the SVG ruleset
        assertTrue(d.select("svg path, svg rect").isNotEmpty(), "benign svg shapes kept")
        assertTrue(d.select("clipPath").isNotEmpty(), "camelCase clipPath preserved (no html->xml round-trip)")
        // local + same-origin link targets are kept (the transform internalises them in PH.3)
        val hrefs = d.select("a[href]").map { it.attr("href") }
        assertTrue(hrefs.any { it.startsWith("#") }, "local #fragment cite kept: $hrefs")
        assertTrue(hrefs.any { it.contains("#bib.bib2") }, "absolute same-origin cite kept for the transform: $hrefs")
    }

    // --- real documents: benign structure survives, threats gone ---------------------------------

    @Test
    fun `real ar5iv document keeps MathML + bare-fragment cites and is inert`() {
        val out = clean(res("ar5iv-clean-1706.03762.html"))
        assertInert(out)
        val d = reparse(out)
        assertTrue(d.select("math[alttext]").isNotEmpty(), "ar5iv MathML (alttext) preserved")
        assertTrue(d.select("a[href^='#']").isNotEmpty(), "bare-fragment in-text cites kept")
        assertTrue(d.select("li[id^=bib.bib]").isNotEmpty(), "resolvable bibliography entries kept")
    }

    @Test
    fun `real native document keeps MathML intent + resolvable bib and is inert`() {
        val out = clean(res("native-clean-2412.19437.html"))
        assertInert(out)
        val d = reparse(out)
        assertTrue(d.select("li[id^=bib.bib]").isNotEmpty(), "native bibliography entries kept")
    }

    @Test
    fun `degraded native document stays inert and keeps the fidelity markers PH3 detects`() {
        val out = clean(res("native-degraded-2510.04905.html"))
        assertInert(out)
        val d = reparse(out)
        assertTrue(d.select(".ltx_missing_citation").isNotEmpty(), "missing-citation marker class survives for PH.3")
        assertTrue(d.select("math[intent]").isNotEmpty(), "native MathML intent= preserved")
    }

    @Test
    fun `sanitise is idempotent (fixed point) across the whole corpus`() {
        for (name in listOf(
            "malicious-battery.html",
            "native-clean-2412.19437.html",
            "native-degraded-2510.04905.html",
            "ar5iv-clean-1706.03762.html",
        )) {
            val once = HtmlSanitizer.sanitize(res(name)) ?: error("null for $name")
            val twice = HtmlSanitizer.sanitize(once) ?: error("null on re-sanitise for $name")
            assertEquals(once, twice, "fixed point violated for $name")
        }
    }

    // --- focused inline cases (mirroring SvgSanitizerTest) ----------------------------------------

    @Test
    fun `script and on-handlers are dropped from HTML and MathML`() {
        val out =
            clean(
                """<section><p onclick="a()">x</p><script>evil()</script>""" +
                    """<math><mi onclick="b()">y</mi></math></section>""",
            )
        val d = reparse(out)
        assertTrue(d.select("script").isEmpty())
        assertNull(d.allElements.firstOrNull { e -> e.attributes().any { it.key.startsWith("on") } })
        assertTrue(d.select("math mi").isNotEmpty(), "the MathML element itself survives (just its handler stripped)")
    }

    @Test
    fun `javascript and data-text-html hrefs are dropped while safe hrefs are kept for the transform`() {
        val out =
            clean(
                """<p><a href="javascript:alert(1)">a</a><a href="data:text/html,x">b</a>""" +
                    """<a href="#S2">c</a><a href="https://arxiv.org/html/2412.19437v1#bib.bib3">d</a></p>""",
            )
        val d = reparse(out)
        val hrefs = d.select("a[href]").map { it.attr("href") }
        assertFalse(hrefs.any { it.startsWith("javascript:") || it.startsWith("data:text/html") }, hrefs.toString())
        assertTrue(hrefs.any { it == "#S2" }, "local fragment kept: $hrefs")
        assertTrue(hrefs.any { it.contains("#bib.bib3") }, "absolute same-origin cite kept: $hrefs")
    }

    @Test
    fun `external image src is stripped but a relative same-origin src is kept`() {
        val out =
            clean("""<figure><img src="https://evil.example/x.png"><img src="2412.19437v1/x1.png"></figure>""")
        val srcs = reparse(out).select("img").map { it.attr("src") }
        assertFalse(srcs.any { it.startsWith("http") }, "no external img src: $srcs")
        assertTrue(srcs.any { it == "2412.19437v1/x1.png" }, "relative same-origin src kept: $srcs")
    }

    @Test
    fun `framing, base, meta-refresh, foreignObject, maction and annotation-xml are dropped`() {
        val out =
            clean(
                """<div><iframe src="https://evil"></iframe><object data="x"></object><embed src="x">""" +
                    """<base href="https://evil/"><meta http-equiv="refresh" content="0;url=https://evil">""" +
                    """<svg><foreignObject><body>html</body></foreignObject><rect/></svg>""" +
                    """<math><maction>m</maction><semantics><annotation-xml encoding="text/html">""" +
                    """<script>z</script></annotation-xml></semantics></math></div>""",
            )
        assertInert(out)
        assertTrue(reparse(out).select("svg rect").isNotEmpty(), "the benign rect inside the cleaned svg survives")
    }

    @Test
    fun `a dangerous style attribute is stripped`() {
        val out = clean("""<p style="background:url('http://evil/x');width:expression(alert(1))">x</p><p>ok</p>""")
        assertNull(reparse(out).allElements.firstOrNull { it.hasAttr("style") }, "dangerous style attr dropped")
    }

    @Test
    fun `blank input and an oversized document return null`() {
        assertNull(HtmlSanitizer.sanitize(""))
        assertNull(HtmlSanitizer.sanitize("   "))
        assertNull(HtmlSanitizer.sanitize("<p>" + "a".repeat(6_000_001) + "</p>"))
    }

    @Test
    fun `a document exceeding the node budget is refused`() {
        val bomb = "<div>" + "<b>x</b>".repeat(260_000) + "</div>"
        assertNull(HtmlSanitizer.sanitize(bomb))
    }
}
