package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.normalizeDoi
import dev.blokz.arxiver.core.network.s2.S2RecommendationsResponse
import dev.blokz.arxiver.data.s2.holdsOnDevice
import dev.blokz.arxiver.data.s2.toDiscoverHit

/**
 * One genuinely-NEW similar paper (P-Discover-MLT PDM.2) — everything the results sheet needs to display
 * and act, already in hand from the ONE recommendations response (the sheet issues zero further network).
 * [arxivId] non-null ⇒ importable via the native arXiv path; else the row is read-only + browser-open
 * (the shipped PT.3 posture for non-arXiv S2 hits).
 */
data class DiscoverHit(
    val s2PaperId: String,
    val title: String,
    val authors: List<String>,
    val year: Int?,
    val venue: String?,
    val abstract: String?,
    val arxivId: ArxivId?,
    val doi: String?,
    val openAccessPdfUrl: String?,
)

/**
 * Typed outcome of a discovery tap — the three failure families stay DISTINCT (the OaResult honesty
 * precedent): a retryable [Error] must never read as "no similar papers", and the two [EmptyNoneReturned]/
 * [EmptyAllLocal] causes get different (honest) copy.
 */
sealed interface DiscoverResult {
    /** Post-dedup survivors, in Semantic Scholar's SPECTER2 order, bounded to [DiscoverSimilarRepository.DISPLAY_CAP]. */
    data class Ready(val hits: List<DiscoverHit>) : DiscoverResult

    /** S2 hasn't indexed this seed (HTTP 404) — non-retryable, NOT an error and NOT "no similar papers". */
    data object SeedNotFound : DiscoverResult

    /** S2 returned nothing for the seed. */
    data object EmptyNoneReturned : DiscoverResult

    /** Everything S2 returned is already on this device — the feature's promise ("new") holds honestly. */
    data object EmptyAllLocal : DiscoverResult

    /** Offline / 429 / 5xx — retryable. */
    data class Error(val error: AppError) : DiscoverResult
}

/**
 * "Discover more like this" (P-Discover-MLT PDM.2): ONE Semantic Scholar recommendations call (the seed's
 * identifier is the only thing that leaves the device), then a read-only dedup against the on-device corpus
 * so results are literally NEW — never overlapping the device-local MLT ([SemanticNeighborsRepository]).
 *
 * v1 trusts S2's server-side SPECTER2 order — deliberately NO on-device re-rank: an ungated embed call
 * would download the bge model from huggingface.co on the tap (an undisclosed egress), and a single-seed
 * cosine is weaker than S2's citation-informed KNN anyway (the bge floor is the gated PDM.5 fast-follow).
 *
 * [recommend] is the transport seam (bound to [SemanticScholarClient.recommendationsForPaper] in DI; a
 * lambda so tests fake the wire without a server — the `embedQuery`-seam precedent).
 */
class DiscoverSimilarRepository(
    private val recommend: suspend (seedId: String, limit: Int) -> AppResult<S2RecommendationsResponse>,
    private val paperDao: PaperDao,
) {
    /** The prefixed S2 seed identifier, or null when the paper is not seedable (the button is hidden). */
    fun seedIdFor(paper: Paper): String? =
        paper.ref.arxivIdOrNull?.let { "ARXIV:${it.value}" }
            ?: normalizeDoi(paper.doi)?.let { "DOI:$it" }

    suspend fun discoverSimilar(seed: Paper): DiscoverResult {
        val seedId = seedIdFor(seed) ?: return DiscoverResult.SeedNotFound
        return when (val result = recommend(seedId, REQUEST_LIMIT)) {
            is AppResult.Failure ->
                when {
                    (result.error as? AppError.Upstream)?.httpCode == 404 -> DiscoverResult.SeedNotFound
                    else -> DiscoverResult.Error(result.error)
                }
            is AppResult.Success -> {
                val returned = result.value.recommendedPapers
                if (returned.isEmpty()) return DiscoverResult.EmptyNoneReturned
                val hits =
                    returned
                        .mapNotNull { it.toDiscoverHit() }
                        .filterNot { paperDao.holdsOnDevice(it) }
                        .take(DISPLAY_CAP)
                // The Ready count is the HONEST post-dedup survivor count — never S2's raw N.
                if (hits.isEmpty()) DiscoverResult.EmptyAllLocal else DiscoverResult.Ready(hits)
            }
        }
    }

    companion object {
        /** Ask for more than the cap so post-dedup survivors can still fill it. */
        internal const val REQUEST_LIMIT = 40

        /** The bounded, calm result-list size. */
        internal const val DISPLAY_CAP = 20
    }
}
