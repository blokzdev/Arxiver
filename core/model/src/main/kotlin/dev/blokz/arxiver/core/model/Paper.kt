package dev.blokz.arxiver.core.model

import java.time.Instant

/** Where a locally cached paper record came from (SPEC-DATA `papers.source`). */
enum class PaperSource {
    SEARCH,
    FOLLOW,
    SHARE_IN,
    MANUAL,
    S2_STUB,
}

data class Paper(
    /** Source-polymorphic identity (P-Sources PS.0). [PaperRef.storageId] is the opaque `papers.id` PK. */
    val ref: PaperRef,
    val latestVersion: Int,
    val title: String,
    val abstract: String,
    val publishedAt: Instant,
    val updatedAt: Instant,
    val primaryCategory: String,
    val categories: List<String>,
    val authors: List<String>,
    val comment: String? = null,
    val journalRef: String? = null,
    val doi: String? = null,
    // arXiv papers default to the synthesized arXiv PDF URL; non-arXiv rows always store their real URL.
    val pdfUrl: String = (ref as? ArxivRef)?.pdfUrl(latestVersion) ?: "",
    val citationCount: Int? = null,
    val source: PaperSource = PaperSource.SEARCH,
    val fetchedAt: Instant = Instant.now(),
    /**
     * The source's own landing page, when it has one (P-Explorer PE.1b). Load-bearing only for a source that
     * publishes neither a DOI nor a PDF url (OSF-hosted PsyArXiv) — there it is the paper's ONLY link.
     */
    val landingUrl: String? = null,
) {
    /**
     * TEMPORARY PS.0 shim — the arXiv-only read sites (`paper.id.value` / `.absUrl()`) keep compiling and
     * behaving identically while they migrate to [ref]. Lossless in PS.0: no non-arXiv `Paper` exists yet,
     * so [PaperRef.arxivIdOrNull] is always non-null. The `error(...)` is a tripwire — if a PS.1 non-arXiv
     * `Paper` is ever read via `.id` it crashes loudly rather than mis-behaving. Deleted as the read sites
     * migrate to `ref` (SPEC-P-SOURCES §6, PS.0).
     */
    @Deprecated("Use ref / ref.storageId", ReplaceWith("ref.arxivIdOrNull!!"))
    val id: ArxivId get() = ref.arxivIdOrNull ?: error("non-arXiv Paper has no ArxivId (PS.0 shim)")

    /**
     * The best public URL for this paper — for share sheets and citations (SPEC-P-SOURCES §5). arXiv →
     * the abstract page (byte-identical to the pre-P-Sources `id.absUrl()`); a non-arXiv paper → its DOI
     * resolver when known (`doi.org/<doi>`, the citeable canonical), else its stored [pdfUrl]. Never
     * synthesizes an arxiv.org URL for a non-arXiv paper — that was the backup URL-mangle bug (§6).
     */
    fun canonicalUrl(): String =
        when (val r = ref) {
            is ArxivRef -> r.absUrl()
            // DOI resolver first (the citeable canonical), then the source's landing page, then the PDF. The
            // landing-page rung exists because an OSF-hosted paper has NO doi and NO pdf — before P-Explorer
            // PE.1b it would have resolved to the empty string, i.e. no link at all.
            else -> doi?.let { "https://doi.org/$it" } ?: landingUrl?.takeIf { it.isNotBlank() } ?: pdfUrl
        }

    /**
     * Whether a downstream consumer (a Claude routine, P-Dispatch §4) can actually fetch this paper's full PDF.
     *
     * Source-aware since P-Explorer PE.0 — this **closes** the recorded `pdf_fetchable` host-reachability
     * deferral. It previously read `ref is ArxivRef`, which is *tautologically false* at its only call site (the
     * non-arXiv branch of `toPayloadPaper`), so every non-arXiv paper told the routine "unfetchable" — including
     * bioRxiv/medRxiv, whose PDFs demonstrably serve real `%PDF` bytes over an already-allowlisted host.
     *
     * The tier comes from the evidence-backed [pdfAccess] map, and we must additionally *hold* a URL to fetch.
     * Never over-promises: an [PdfAccess.IN_APP] source with no [pdfUrl] still reports false, and the per-hop
     * egress gate + `PdfDownloader`'s `%PDF` magic-byte guard back-stop any residual optimism.
     */
    fun isPdfFetchable(): Boolean = ref.origin.pdfAccess() == PdfAccess.IN_APP && pdfUrl.isNotBlank()
}
