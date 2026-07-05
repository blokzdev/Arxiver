package dev.blokz.arxiver.core.model

import java.time.Instant

/**
 * A non-arXiv paper captured from a search hit, ready to be persisted as a real [Paper] on import
 * (SPEC-P-SOURCES §2, PS.1). It is the value carried across the search→import seam so import never has to
 * re-fetch the source (no source get-by-id endpoint exists, and a re-search would cost a second egress +
 * rate-limit slot for a paper the user already saw).
 *
 * Public metadata ONLY — title/abstract/authors/date/pdf url. No tokens, no bytes (the red lines hold: a
 * draft is safe to cache in memory). [abstract] is the FULL untruncated text (the search DTO truncates a
 * snippet for the model; the stored paper keeps the whole abstract as its offline read surface).
 *
 * [origin] is never [Source.ARXIV] — arXiv papers import through the native Atom path, not a draft.
 */
data class ExternalPaperDraft(
    val origin: Source,
    val nativeId: String,
    val title: String,
    val abstract: String,
    val authors: List<String>,
    val publishedAt: Instant,
    val pdfUrl: String,
) {
    init {
        require(origin != Source.ARXIV) { "ExternalPaperDraft is for non-arXiv sources — arXiv uses the Atom path" }
    }
}
