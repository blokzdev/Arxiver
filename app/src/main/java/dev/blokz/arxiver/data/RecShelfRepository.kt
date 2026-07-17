package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.database.dao.LibraryDao
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.database.dao.PaperFeedbackDao
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.network.s2.S2RecommendationsResponse
import dev.blokz.arxiver.data.s2.dedupSurvivors
import dev.blokz.arxiver.data.s2.s2SeedId
import kotlin.random.Random

/**
 * Typed outcome of a "Recommended for you" fetch (P-RecShelf PRS.2). Mirrors [DiscoverResult]'s
 * honesty contract — a retryable [Error] must never read as "nothing to recommend", and the empty
 * families get distinct copy — with two list-seeded differences: [NoSeeds] is the PRE-network gate
 * (the transport is never invoked), and [NotRecommendable] is the TERMINAL 400 (S2 resolved none of
 * the seeds we sent; the live-probed 400 body is discarded by the client and indistinguishable from
 * an empty seed list, so retrying the same library cannot help — no retry affordance).
 */
sealed interface RecShelfResult {
    /** Post-dedup survivors, in Semantic Scholar's SPECTER2 order, bounded to [RecShelfRepository.DISPLAY_CAP]. */
    data class Ready(val hits: List<DiscoverHit>) : RecShelfResult

    /** Zero seedable positives on device — the shelf stays silent; NO network call was made. */
    data object NoSeeds : RecShelfResult

    /** S2 resolved none of the sent seeds (HTTP 400) — terminal for this library state, not retryable. */
    data object NotRecommendable : RecShelfResult

    /** S2 returned nothing for the seeds. */
    data object EmptyNoneReturned : RecShelfResult

    /** Everything S2 returned is already on this device — the shelf's promise ("new") holds honestly. */
    data object EmptyAllLocal : RecShelfResult

    /** Offline / 429 / 5xx — retryable. */
    data class Error(val error: AppError) : RecShelfResult
}

/**
 * "Recommended for you" (P-RecShelf PRS.2): ONE list-seeded Semantic Scholar recommendations call over
 * the user's OWN positive signals, then the shared read-only dedup ([holdsOnDevice]) so every row is
 * genuinely NEW. Stateless — memoization/refresh policy belongs to the caller (the Today ViewModel).
 *
 * **Seed policy (Co-Founder-approved 2026-07-17):** positives = library saves (`added_at DESC`) ∪
 * thumb-ups (`created_at DESC`), first-occurrence-wins; each row resolves to a prefixed public id
 * (arXiv-parse-first on the row PK — the same key persistence uses — else the stored `doi_norm`;
 * neither → dropped as non-seedable). The final list BLENDS [RECENT_COUNT] most-recent seedables with
 * [SAMPLE_COUNT] uniform-sampled older ones (burst-bubble guard: a weekend of saves on one topic must
 * not monopolize the seed set). **NO negatives in v1** — structurally: the [recommend] seam carries no
 * negative list at all. The `paper_feedback` egress is the KDoc-sanctioned carve-out: prefixed PUBLIC
 * paper ids only — the signal/source/score columns never leave the device.
 *
 * **Disclosure seam:** the caller discloses `seedIds().size` on the invitation card and passes THAT
 * list to [recommend] — one computation, so the disclosed count and the sent list cannot drift. As a
 * second line of defense, [seedIds] is IDEMPOTENT per library state (the sampler is seeded from the
 * candidate content, never from shared RNG state): even a caller that recomputes between disclosing
 * and sending gets the identical list.
 *
 * [transport] is the wire seam (bound to `SemanticScholarClient.recommendationsFromLists` with
 * positives only in DI; a lambda so tests fake the wire — the [DiscoverSimilarRepository] precedent).
 */
class RecShelfRepository(
    private val transport: suspend (positiveIds: List<String>, limit: Int) -> AppResult<S2RecommendationsResponse>,
    private val paperDao: PaperDao,
    private val libraryDao: LibraryDao,
    private val feedbackDao: PaperFeedbackDao,
) {
    /**
     * The FINAL seed-id list — post-resolution, post-drop, deduped on the RESOLVED seed string,
     * blended, capped. `[]` means [RecShelfResult.NoSeeds]: render nothing (or the cold-start silence)
     * and never call [recommend]. Bound worth knowing: saves fill the candidate pool before thumb-ups,
     * so past [CANDIDATE_POOL_CAP] saves the thumb signal is crowded out — the approved saves-dominant
     * policy (a recency-MERGED pool across both sources is a recorded backlog item).
     */
    suspend fun seedIds(): List<String> {
        // Recency-ordered candidate row ids, saves first then thumb-ups, first occurrence wins. The pool
        // is capped BEFORE the bulk resolve (SQLite bind-arg ceiling; sampling needs a pool, not a census).
        val candidateIds =
            (libraryDao.paperIdsByRecency(CANDIDATE_POOL_CAP) + feedbackDao.positivePaperIds())
                .distinct()
                .take(CANDIDATE_POOL_CAP)
        if (candidateIds.isEmpty()) return emptyList()
        val entities = paperDao.papersByIds(candidateIds).associateBy { it.id }
        // distinct() again on the RESOLVED strings: two rows sharing a doi_norm must yield ONE seed,
        // or the disclosed count over-states what S2 actually receives.
        val seedable =
            candidateIds
                .mapNotNull { id -> entities[id]?.let(::seedIdFor) }
                .distinct()
        val recent = seedable.take(RECENT_COUNT)
        val older = seedable.drop(RECENT_COUNT)
        // Content-seeded sampler: same library state → same sample. Never a shared mutable RNG — a
        // second seedIds() call between "disclose" and "send" must not re-roll the list.
        return recent + older.shuffled(Random(candidateIds.hashCode())).take(SAMPLE_COUNT)
    }

    /**
     * Fire the ONE recommendations call for [seedIds] (the same list the caller disclosed) and map to
     * honest typed states. Pre-guards the empty list — the live-probed 400 cannot distinguish "no seeds"
     * from "none resolved", so the distinction is made HERE, before any byte leaves the device.
     */
    suspend fun recommend(seedIds: List<String>): RecShelfResult {
        if (seedIds.isEmpty()) return RecShelfResult.NoSeeds
        return when (val result = transport(seedIds, REQUEST_LIMIT)) {
            is AppResult.Failure ->
                when {
                    (result.error as? AppError.Upstream)?.httpCode == 400 -> RecShelfResult.NotRecommendable
                    else -> RecShelfResult.Error(result.error)
                }
            is AppResult.Success -> {
                val returned = result.value.recommendedPapers
                if (returned.isEmpty()) return RecShelfResult.EmptyNoneReturned
                // The Ready count is the HONEST post-dedup survivor count — never S2's raw N. The
                // pipeline itself is the shared dedupSurvivors (one author for both surfaces).
                val hits = dedupSurvivors(returned, paperDao, DISPLAY_CAP)
                if (hits.isEmpty()) RecShelfResult.EmptyAllLocal else RecShelfResult.Ready(hits)
            }
        }
    }

    /**
     * A stored row's prefixed S2 seed id, or null when non-seedable. arXiv-parse-FIRST on the row PK
     * (arXiv-origin rows store the bare arXiv id; non-arXiv PKs are `wire:native` and never parse), then
     * the stored `doi_norm` (already normalized at persist time — not re-derived here). The rule itself
     * is the shared [s2SeedId] — this only adapts the entity's fields into it.
     */
    private fun seedIdFor(entity: PaperEntity): String? = s2SeedId(ArxivId.parse(entity.id)?.first, entity.doiNorm)

    companion object {
        /** Most-recent seedable positives always included — the "what I'm into now" half of the blend. */
        internal const val RECENT_COUNT = 10

        /** Uniform-sampled older positives — the burst-bubble guard half. Total ≤ 20 = the client clamp. */
        internal const val SAMPLE_COUNT = 10

        /** Candidate rows considered before resolution (bind-arg-safe; plenty for a uniform sample). */
        internal const val CANDIDATE_POOL_CAP = 400

        /** Ask for more than the cap so post-dedup survivors can still fill it. */
        internal const val REQUEST_LIMIT = 30

        /** The bounded, calm shelf size. */
        internal const val DISPLAY_CAP = 10
    }
}
