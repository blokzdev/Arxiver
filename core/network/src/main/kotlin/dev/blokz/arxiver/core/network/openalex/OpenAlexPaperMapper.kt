package dev.blokz.arxiver.core.network.openalex

import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.PaperSource
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.model.resolvePaperRef
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Map an OpenAlex search hit to a domain [Paper] (P-Explorer PE.3) — the search-side sibling of the follow
 * ingest path, honoring the same identity rules:
 *
 * - **Identity** = the bare DOI when the source publishes one, else the OpenAlex work id (`W…`) — PsyArXiv is
 *   99.4% DOI-null (PE.1b). The DOI keys **verbatim** (`.vN` intact): normalization is a *lookup* concern at the
 *   reuse seams (`paperIdByDoi` matches `doi_norm`), never an identity rewrite.
 * - **arXiv cross-posts collapse**: [OpenAlexWork.arxivLandingUrl] feeds [resolvePaperRef], so a hit that is also
 *   on arXiv keys under the bare arXiv id — and its [Paper.pdfUrl] falls back to the synthesized arXiv PDF when
 *   OpenAlex carries no OA url, so a cross-post never loses in-app reading to a null `best_oa_location`.
 * - **Reachability guard**: a hit with no DOI, no landing page, and no PDF has no way out to the web at all
 *   (census tail) — dropped rather than rendered as a dead card.
 *
 * Census-honest degradation (2026-07-10): SSRN hits carry no abstract (100%) and no PDF (99.5%); Research Square
 * drops abstracts on 86% of recent works. Missing fields map to blanks — the UI renders an honest short card —
 * never to a skipped hit.
 */
fun OpenAlexWork.toPaper(source: Source): Paper? {
    val nativeId = bareDoi() ?: openAlexId() ?: return null
    val ref = resolvePaperRef(arxivId = arxivLandingUrl(), origin = source, nativeId = nativeId)
    val published =
        publicationDate
            ?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull() }
            ?: Instant.EPOCH
    val paper =
        Paper(
            ref = ref,
            latestVersion = 1,
            title = title.orEmpty(),
            abstract = abstractText().orEmpty(),
            publishedAt = published,
            updatedAt = published,
            primaryCategory = primaryTopic?.field?.displayName?.takeIf { it.isNotBlank() }.orEmpty(),
            categories = listOfNotNull(primaryTopic?.field?.displayName?.takeIf { it.isNotBlank() }),
            authors = authorNames(),
            doi = bareDoi(),
            pdfUrl = oaPdfUrl() ?: (ref as? ArxivRef)?.pdfUrl(1) ?: "",
            landingUrl = landingPageUrl(),
            source = PaperSource.SEARCH,
        )
    return paper.takeIf { it.canonicalUrl().isNotBlank() }
}
