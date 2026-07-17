package dev.blokz.arxiver.data.s2

import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.PaperSource
import dev.blokz.arxiver.core.model.normalizeDoi
import dev.blokz.arxiver.core.network.s2.S2SearchPaper
import dev.blokz.arxiver.data.DiscoverHit

/**
 * A displayable candidate needs a title + a stable S2 id; identity fields are normalized here once.
 * (This file — the mapping + the dedup contract below — is hoisted out of `DiscoverSimilarRepository`
 * in P-RecShelf PRS.2 so the single-seed sheet and the library-wide shelf can NEVER disagree on
 * identity normalization or on what counts as "already on this device".)
 */
internal fun S2SearchPaper.toDiscoverHit(): DiscoverHit? {
    val id = paperId?.takeIf { it.isNotBlank() } ?: return null
    val shownTitle = title?.takeIf { it.isNotBlank() } ?: return null
    return DiscoverHit(
        s2PaperId = id,
        title = shownTitle,
        authors = authors.mapNotNull { it.name },
        year = year,
        venue = venue?.takeIf { it.isNotBlank() },
        abstract = abstract?.takeIf { it.isNotBlank() },
        arxivId = externalIds?.ArXiv?.let { ArxivId.parse(it)?.first },
        doi = normalizeDoi(externalIds?.DOI),
        openAccessPdfUrl = openAccessPdf?.url,
    )
}

/**
 * True when the candidate already exists as a REAL on-device row. Keyed exactly like persistence
 * (arXiv-parse-first per `resolvePaperRef`'s contract, then the normalized-DOI crosswalk), so dedup and
 * a later save can never disagree. A `S2_STUB` citation-graph stub does NOT count as "on device" —
 * candidates held only as stubs still legitimately surface (the stub has no abstract, no PDF, no shelf
 * presence; the `unembeddedPapers` worker skips it too).
 */
internal suspend fun PaperDao.holdsOnDevice(hit: DiscoverHit): Boolean {
    val byArxiv = hit.arxivId?.let { paperById(it.value) }
    if (byArxiv != null && byArxiv.source != PaperSource.S2_STUB.name) return true
    val doiRowId = hit.doi?.let { paperIdByDoi(it) } ?: return false
    val byDoi = paperById(doiRowId)
    return byDoi != null && byDoi.source != PaperSource.S2_STUB.name
}
