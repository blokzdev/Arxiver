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
    val id: ArxivId,
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
    val pdfUrl: String = id.pdfUrl(latestVersion),
    val citationCount: Int? = null,
    val source: PaperSource = PaperSource.SEARCH,
    val fetchedAt: Instant = Instant.now(),
)
