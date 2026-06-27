package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.model.ArxivId
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Turns an already-**sanitized** arXiv HTML-edition body (the output of [HtmlSanitizer.sanitize]) into
 * a reader [ReaderDocument]: computes the conversion-[FidelityReport], extracts the LaTeXML article
 * (dropping site chrome), internalizes every link to the in-app `arxiver://` scheme, replaces images
 * with figcaption placeholders (MVP — no fetch; figures land in PH.5), keeps `<math>` verbatim for
 * native WebView MathML, and proves zero external host survives. Pure JVM (jsoup) → golden-testable.
 * (Phase P-HTML PH.3, SPEC-P-HTML §4/§9.)
 *
 * **Never re-sanitizes** — the caller (HtmlFetcher) always sanitizes first; this only rewrites/extracts.
 * [ReaderDocWriter] wraps the returned [ReaderDocument.bodyHtml] into the full self-contained document.
 */
object HtmlReaderTransform {
    /** A cited bibliography is "resolvable" iff at least this many in-text bib cites exist with no bib list. */
    private const val MIN_CITES_FOR_BIB_CHECK = 3

    /** DEGRADED when this fraction of citation *attempts* (resolved + missing) failed to convert. */
    private const val MISSING_DENSITY = 0.5f

    private const val MAX_LABEL = 120

    private val BIB_ID = Regex("""^bib\.bib\d+$""")
    private val SECTION_ID = Regex("""^S\d+(\.SS\d+)*$""")
    private val FIGURE_ID = Regex("""\.F\d+$""")
    private val TABLE_ID = Regex("""\.T\d+$""")

    /** External after rewrite = anything still pointing off-app over the wire. */
    private val EXTERNAL_REF = Regex("""^\s*(?:https?:)?//""", RegexOption.IGNORE_CASE)

    /** Schemes the sanitizer should already have removed; if one survives, drop it (never wrap). */
    private val DANGEROUS_SCHEME = Regex("""^\s*(?:javascript:|vbscript:|data:(?!image/))""", RegexOption.IGNORE_CASE)

    /** A link to some OTHER paper's HTML edition (native or ar5iv); group 1 = that paper's id path. */
    private val OTHER_HTML_LINK =
        Regex(
            """^https?://(?:www\.)?(?:arxiv\.org|ar5iv\.labs\.arxiv\.org)/html/(.+?)(?:v\d+)?(?:#.*)?$""",
            RegexOption.IGNORE_CASE,
        )

    fun transform(
        sanitizedBody: String,
        id: ArxivId,
        source: HtmlSource,
    ): ReaderDocument? {
        val doc =
            try {
                Jsoup.parseBodyFragment(sanitizedBody)
            } catch (_: Exception) {
                return null
            }
        doc.outputSettings().prettyPrint(false)
        val body = doc.body()

        // (a) Fidelity on the FULL body, BEFORE extraction (a bibliography may live outside the article).
        val fidelity = computeFidelity(body)

        // (b) Extract the LaTeXML content root (the article, or the page-main wrapper); else fall back to
        // the whole body. Strip site chrome by precise class always; only the body fallback also drops
        // bare nav/header/footer (so a mis-nested document can't lose content to an over-broad strip).
        val root =
            body.selectFirst("article.ltx_document")
                ?: body.selectFirst(".ltx_page_main")
                ?: body
        root.select("nav.ltx_page_navbar, nav.ltx_TOC, .ltx_page_header, .ltx_page_footer").remove()
        if (root === body) root.select("nav, header, footer").remove()

        // (c) Internalize every link (source-aware, id-scoped, total).
        rewriteHrefs(root, id, source)

        // (d) Images → figcaption placeholder (MVP: no fetch; keep any surrounding <figure>/<figcaption>).
        for (img in root.select("img")) {
            val placeholder = Element("span").addClass("ltx_placeholder").text("Figure unavailable — download to view")
            img.replaceWith(placeholder)
        }

        // (e) <math> kept verbatim (native MathML; sanitizer preserved alttext/intent/display).

        // (f) Defence-in-depth: a surviving external host means the rewrite/sanitize missed something.
        if (hasExternalRef(root)) return null

        // (g) Anchors for the PH.6 table-of-contents (emitted, unused until then).
        val anchors = extractAnchors(root)

        val bodyHtml = root.html().trim()
        if (bodyHtml.isBlank()) return null
        return ReaderDocument(bodyHtml = bodyHtml, fidelity = fidelity, anchors = anchors, source = source)
    }

    /** Pure, reusable by PH.6 / the cache-hit path over a stored body. */
    fun extractAnchors(root: Element): List<ReaderAnchor> =
        root.select("[id]").mapNotNull { el ->
            val anchorId = el.id()
            val type =
                when {
                    BIB_ID.matches(anchorId) -> AnchorType.BIBLIOGRAPHY
                    FIGURE_ID.containsMatchIn(anchorId) -> AnchorType.FIGURE
                    TABLE_ID.containsMatchIn(anchorId) -> AnchorType.TABLE
                    SECTION_ID.matches(anchorId) -> AnchorType.SECTION
                    else -> null
                } ?: return@mapNotNull null
            val label =
                (el.selectFirst(".ltx_title, h1, h2, h3, h4, h5, h6, figcaption")?.text() ?: el.ownText())
                    .trim().take(MAX_LABEL)
            ReaderAnchor(anchorId, type, label)
        }

    private fun computeFidelity(body: Element): FidelityReport {
        val missing = body.select(".ltx_missing_citation").size
        val resolvableBib = body.select("[id]").count { BIB_ID.matches(it.id()) }
        val bibCites = body.select("a.ltx_ref").count { it.attr("href").contains("#bib") }
        val attempts = bibCites + missing
        val densityDegraded = attempts > 0 && missing.toFloat() / attempts >= MISSING_DENSITY
        val noBibDegraded = bibCites >= MIN_CITES_FOR_BIB_CHECK && resolvableBib == 0
        val reason =
            when {
                noBibDegraded -> "no resolvable bibliography ($bibCites in-text cites, 0 entries)"
                densityDegraded -> "high citation-conversion loss ($missing of $attempts attempts failed)"
                else -> null
            }
        return FidelityReport(
            fidelity = if (reason != null) Fidelity.DEGRADED else Fidelity.OK,
            reason = reason,
            missingCitationCount = missing,
            resolvableBibCount = resolvableBib,
            bibTargetingCiteCount = bibCites,
        )
    }

    private fun rewriteHrefs(
        root: Element,
        id: ArxivId,
        source: HtmlSource,
    ) {
        val idVal = id.value
        val enc = idVal.replace("/", "%2F") // legacy slash ids → one path segment (mirrors RichHtml)
        // Both same-origin forms are "this paper" regardless of which source served the doc.
        val selfPrefixes =
            listOf(
                "https://arxiv.org/html/$idVal",
                "http://arxiv.org/html/$idVal",
                "https://ar5iv.labs.arxiv.org/html/$idVal",
            )
        for (el in root.select("[href]")) {
            val href = el.attr("href").trim()
            when {
                href.isEmpty() -> {}
                // 1. bare same-doc fragment
                href.startsWith("#") -> el.attr("href", anchorUri(enc, href.removePrefix("#")))
                // 2. absolute same-origin link to THIS paper (boundary-anchored, not a bare prefix)
                else -> {
                    val selfFrag = selfPrefixes.firstNotNullOfOrNull { matchSelf(href, it) }
                    when {
                        selfFrag != null -> el.attr("href", anchorUri(enc, selfFrag.ifEmpty { null }))
                        // 3. a different paper's HTML edition → open that paper in-app
                        OTHER_HTML_LINK.matchEntire(href) != null -> {
                            val other = OTHER_HTML_LINK.matchEntire(href)!!.groupValues[1].trimEnd('/')
                            el.attr("href", "arxiver://paper/${other.replace("/", "%2F")}")
                        }
                        // 4. dangerous scheme that slipped the sanitizer → strip the href entirely
                        DANGEROUS_SCHEME.containsMatchIn(href) -> el.removeAttr("href")
                        // 5. any other external link → preserve, opened via an explicit confirm (PH.4)
                        else -> {
                            el.attr("href", "arxiver://external?url=" + URLEncoder.encode(href, "UTF-8"))
                            el.attr("data-external", "true")
                            el.addClass("ltx_external")
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns the fragment (possibly empty) when [href] is the same paper's URL [prefix] at an id
     * boundary — only an optional `v<digits>` then an optional `#fragment` may follow [prefix]. Returns
     * null otherwise (so `/html/2412.19437` can never falsely match `/html/2412.194370`).
     */
    private fun matchSelf(
        href: String,
        prefix: String,
    ): String? {
        if (!href.startsWith(prefix)) return null
        val rest = href.substring(prefix.length)
        val m = Regex("""^(?:v\d+)?(?:#(.*))?$""").matchEntire(rest) ?: return null
        return m.groupValues[1]
    }

    private fun anchorUri(
        encId: String,
        frag: String?,
    ): String =
        if (frag.isNullOrEmpty()) {
            "arxiver://anchor/$encId"
        } else {
            "arxiver://anchor/$encId?frag=" + URLEncoder.encode(frag, "UTF-8")
        }

    private fun hasExternalRef(root: Element): Boolean =
        root.select("[href], [src], [xlink:href]").any { el ->
            listOf("href", "src", "xlink:href").any { attr ->
                val v = el.attr(attr).trim()
                v.isNotEmpty() && EXTERNAL_REF.containsMatchIn(v)
            }
        }
}
