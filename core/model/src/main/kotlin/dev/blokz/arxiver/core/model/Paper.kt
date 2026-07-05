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
}
