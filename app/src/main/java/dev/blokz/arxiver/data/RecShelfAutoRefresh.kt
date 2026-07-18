package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.model.ArxivId
import kotlinx.serialization.Serializable

/**
 * The opt-in auto-refresh substrate for the "Recommended for you" shelf (P-RecShelf PRS.4). A
 * DataStore-JSON cache (NO Room table — DB stays 17) plus a PURE [RecShelfRefreshPolicy] that decides,
 * from timestamps alone, when a background auto-refresh may fire. The policy is separated out and
 * exhaustively unit-tested because a naive version (refresh whenever the shelf is visible) would hammer
 * Semantic Scholar's shared keyless pool during an outage — the review's anti-backoff blocker.
 */

/** A cached recommendation row — the serializable projection of [DiscoverHit] (its `ArxivId` flattened to a String). */
@Serializable
data class CachedHit(
    val s2PaperId: String,
    val title: String,
    val authors: List<String>,
    val year: Int? = null,
    val venue: String? = null,
    val abstract: String? = null,
    val arxivId: String? = null,
    val doi: String? = null,
    val openAccessPdfUrl: String? = null,
)

/**
 * The persisted shelf cache. [fetchedAtMs] is the last SUCCESSFUL fetch (drives freshness + the staleness
 * label); [hits] is that success's rows (empty until the first success). [failedAttempts] + [nextAllowedAtMs]
 * are the backoff state — a failure advances them but NEVER touches [fetchedAtMs]/[hits], so a transient
 * outage keeps showing the last good rows (stale-while-error) instead of blanking the shelf.
 */
@Serializable
data class RecShelfCache(
    val fetchedAtMs: Long,
    val hits: List<CachedHit>,
    val failedAttempts: Int = 0,
    val nextAllowedAtMs: Long = 0,
)

fun DiscoverHit.toCached(): CachedHit =
    CachedHit(s2PaperId, title, authors, year, venue, abstract, arxivId?.value, doi, openAccessPdfUrl)

fun CachedHit.toHit(): DiscoverHit =
    DiscoverHit(s2PaperId, title, authors, year, venue, abstract, arxivId?.let { ArxivId(it) }, doi, openAccessPdfUrl)

/**
 * Pure timestamp arithmetic for the auto-refresh trigger — no I/O, no clock of its own (the caller passes
 * `now`), so every branch is deterministically unit-testable.
 */
object RecShelfRefreshPolicy {
    /** A successful fetch stays "fresh" (no auto-refetch) for this long. */
    const val TTL_MS: Long = 24L * 60 * 60 * 1000

    /** First-failure backoff; doubles per consecutive failure, capped at [MAX_BACKOFF_MS]. */
    const val BASE_BACKOFF_MS: Long = 30L * 60 * 1000

    /** Backoff ceiling — a long-dead endpoint is retried at most once a day, never more. */
    const val MAX_BACKOFF_MS: Long = 24L * 60 * 60 * 1000

    /** A last success within the TTL — render from cache, don't refetch. `fetchedAtMs == 0` (never succeeded) is not fresh. */
    fun isFresh(
        now: Long,
        cache: RecShelfCache,
    ): Boolean = cache.fetchedAtMs > 0 && now - cache.fetchedAtMs < TTL_MS

    /** Exponential backoff for [failedAttempts] consecutive failures: base·2^(n-1), capped. 0 attempts → no delay. */
    fun backoffDelayMs(failedAttempts: Int): Long {
        if (failedAttempts <= 0) return 0
        // Cap the shift well before Long overflow, then clamp to the ceiling.
        val shifted = BASE_BACKOFF_MS shl (failedAttempts - 1).coerceAtMost(20)
        return if (shifted <= 0 || shifted > MAX_BACKOFF_MS) MAX_BACKOFF_MS else shifted
    }

    /** The earliest time a background auto-refresh may fire after [failedAttempts] failures ending at [now]. */
    fun nextAllowedAt(
        now: Long,
        failedAttempts: Int,
    ): Long = now + backoffDelayMs(failedAttempts)

    /**
     * May a BACKGROUND auto-refresh egress now? No cache → yes (never tried). Fresh cache → no (still good).
     * Stale cache → only once the backoff window has elapsed. (A user's explicit Refresh tap bypasses this —
     * it is not "background".)
     */
    fun mayAutoRefresh(
        now: Long,
        cache: RecShelfCache?,
    ): Boolean {
        if (cache == null) return true
        if (isFresh(now, cache)) return false
        return now >= cache.nextAllowedAtMs
    }
}
