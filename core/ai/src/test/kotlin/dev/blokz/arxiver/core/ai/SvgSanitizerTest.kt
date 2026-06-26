package dev.blokz.arxiver.core.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adversarial golden corpus for the PA P-Share PS.1 SVG allowlist sanitizer. Pure JVM. Malicious
 * payloads must be neutralised to shape-only (no script / no external fetch); benign vector content
 * must survive; non-svg input falls back to null (→ the caller's inert code box).
 */
class SvgSanitizerTest {
    private fun clean(raw: String): String = SvgSanitizer.sanitize(raw)!!

    // --- benign content survives ---

    @Test
    fun `a benign svg keeps its shapes and attributes`() {
        val out =
            clean("""<svg viewBox="0 0 10 10"><circle cx="5" cy="5" r="4" fill="#f00"/><path d="M0 0 L10 10"/></svg>""")
        assertTrue(out.startsWith("<svg"), out)
        assertTrue(out.contains("<circle") && out.contains("<path"), out)
        assertTrue(out.contains("viewBox") && out.contains("""fill="#f00""""), out)
    }

    @Test
    fun `camelCase svg elements are preserved (xml parse, not html-lowercased)`() {
        val out = clean("""<svg><clipPath id="c"><rect width="2" height="2"/></clipPath></svg>""")
        assertTrue(out.contains("clipPath"), "case-preserved element: $out")
    }

    @Test
    fun `a local fragment paint reference is kept`() {
        val out =
            clean(
                """<svg><defs><linearGradient id="g"><stop offset="0"/></linearGradient></defs>""" +
                    """<rect fill="url(#g)"/></svg>""",
            )
        assertTrue(out.contains("url(#g)"), out)
        assertTrue(out.contains("linearGradient"), out)
    }

    @Test
    fun `a local use reference survives`() {
        val out = clean("""<svg><defs><rect id="r" width="2" height="2"/></defs><use href="#r"/></svg>""")
        assertTrue(out.contains("<use") && out.contains("#r"), out)
    }

    @Test
    fun `a benign inline style is kept`() {
        val out = clean("""<svg><style>.c{fill:red}</style><rect class="c"/></svg>""")
        assertTrue(out.contains("fill:red"), out)
    }

    // --- malicious content is neutralised ---

    @Test
    fun `script is dropped, including case-obfuscated`() {
        assertFalse(clean("""<svg><script>alert(1)</script><rect/></svg>""").lowercase().contains("script"))
        assertFalse(clean("""<svg><ScRiPt>alert(1)</ScRiPt><g/></svg>""").lowercase().contains("script"))
        assertTrue(
            clean("""<svg><script>x</script><rect fill="red"/></svg>""").contains("<rect"),
            "benign sibling survives",
        )
    }

    @Test
    fun `foreignObject (arbitrary HTML) is dropped`() {
        val out =
            clean("""<svg><foreignObject><body><img src="x" onerror="evil()"/></body></foreignObject><circle/></svg>""")
        assertFalse(out.lowercase().contains("foreignobject"), out)
        assertFalse(out.lowercase().contains("<body") || out.contains("onerror"), out)
        assertTrue(out.contains("<circle"), out)
    }

    @Test
    fun `event handlers are stripped off every element`() {
        val out = clean("""<svg onload="boom()"><rect onclick="x()" onmouseover="y()" fill="blue"/></svg>""")
        assertFalse(out.contains("onload") || out.contains("onclick") || out.contains("onmouseover"), out)
        assertTrue(out.contains("""fill="blue""""), "benign attribute kept: $out")
    }

    @Test
    fun `external resource references are removed`() {
        // <image href=http> — image not allowlisted AND external; <rect fill=url(http)> — attr stripped.
        val out =
            clean("""<svg><image href="http://evil.example/x.png"/><rect fill="url(http://evil.example/p)"/></svg>""")
        assertFalse(out.lowercase().contains("<image"), out)
        assertFalse(out.contains("evil.example"), "no external ref survives: $out")
        assertTrue(out.contains("<rect"), "the rect shape itself is kept (just its external fill stripped)")
    }

    @Test
    fun `javascript and data-html hrefs are removed`() {
        // <a> is not allowlisted -> dropped with subtree; the javascript: uri must never survive.
        val out = clean("""<svg><a href="javascript:alert(1)"><rect/></a><circle/></svg>""")
        assertFalse(out.lowercase().contains("javascript"), out)
        assertTrue(out.startsWith("<svg") && out.contains("<circle"), out)
    }

    @Test
    fun `a dangerous stylesheet body is emptied`() {
        val out =
            clean("""<svg><style>@import url(http://evil.example/s.css);.c{fill:red}</style><rect class="c"/></svg>""")
        assertFalse(out.contains("@import") || out.contains("evil.example"), out)
        assertTrue(out.contains("<rect"), out)
    }

    // --- structural / fallback ---

    @Test
    fun `input with no svg root returns null (caller shows the inert code box)`() {
        assertNull(SvgSanitizer.sanitize("not an svg at all"))
        assertNull(SvgSanitizer.sanitize("<div><p>hello</p></div>"))
        assertNull(SvgSanitizer.sanitize(""))
    }

    @Test
    fun `implausibly large input is refused`() {
        assertNull(SvgSanitizer.sanitize("<svg>" + "a".repeat(200_001) + "</svg>"))
    }

    @Test
    fun `surrounding wrapper content is dropped — only the svg subtree is emitted`() {
        val out = clean("""<div>junk<svg><rect/></svg>more</div>""")
        assertTrue(out.startsWith("<svg") && out.contains("<rect"), out)
        assertFalse(out.contains("junk") || out.contains("more"), out)
    }
}
