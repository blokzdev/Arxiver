package dev.blokz.arxiver.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PRS.4 — the PURE auto-refresh policy. This is the anti-backoff correctness the review flagged: a naive
 * "refresh whenever visible" would hammer S2's shared keyless pool during an outage. Every branch is
 * deterministic (the caller passes `now`), so it's exhaustively testable without a clock or I/O.
 */
class RecShelfRefreshPolicyTest {
    private val ttl = RecShelfRefreshPolicy.TTL_MS
    private val base = RecShelfRefreshPolicy.BASE_BACKOFF_MS
    private val maxB = RecShelfRefreshPolicy.MAX_BACKOFF_MS

    private fun cache(
        fetchedAtMs: Long = 0,
        failedAttempts: Int = 0,
        nextAllowedAtMs: Long = 0,
        hits: List<CachedHit> = emptyList(),
    ) = RecShelfCache(fetchedAtMs, hits, failedAttempts, nextAllowedAtMs)

    @Test
    fun `a fetch inside the TTL is fresh, past it is stale, and never-succeeded is never fresh`() {
        val now = 10 * ttl
        assertTrue(RecShelfRefreshPolicy.isFresh(now, cache(fetchedAtMs = now - ttl / 2)))
        assertFalse(RecShelfRefreshPolicy.isFresh(now, cache(fetchedAtMs = now - ttl - 1)))
        // fetchedAtMs == 0 (never succeeded) must not read as fresh even though now-0 could be < TTL for small now.
        assertFalse(RecShelfRefreshPolicy.isFresh(now = 1, cache(fetchedAtMs = 0)))
    }

    @Test
    fun `backoff is exponential from the base, capped, and zero for no failures`() {
        assertEquals(0L, RecShelfRefreshPolicy.backoffDelayMs(0))
        assertEquals(base, RecShelfRefreshPolicy.backoffDelayMs(1))
        assertEquals(base * 2, RecShelfRefreshPolicy.backoffDelayMs(2))
        assertEquals(base * 4, RecShelfRefreshPolicy.backoffDelayMs(3))
        // Grows until the ceiling, then clamps — never overflows to a negative or an unbounded delay.
        assertEquals(maxB, RecShelfRefreshPolicy.backoffDelayMs(100))
        assertTrue(RecShelfRefreshPolicy.backoffDelayMs(50) in base..maxB)
    }

    @Test
    fun `mayAutoRefresh — no cache yes, fresh no, stale gated on the backoff window`() {
        val now = 10 * ttl
        assertTrue(RecShelfRefreshPolicy.mayAutoRefresh(now, cache = null), "never tried → may refresh")
        assertFalse(
            RecShelfRefreshPolicy.mayAutoRefresh(now, cache(fetchedAtMs = now - ttl / 2)),
            "fresh → no refresh",
        )
        // Stale, backoff window NOT elapsed → blocked (this is the anti-hammer guarantee).
        assertFalse(
            RecShelfRefreshPolicy.mayAutoRefresh(
                now,
                cache(fetchedAtMs = 0, failedAttempts = 3, nextAllowedAtMs = now + 1),
            ),
        )
        // Stale, backoff window elapsed → allowed.
        assertTrue(
            RecShelfRefreshPolicy.mayAutoRefresh(
                now,
                cache(fetchedAtMs = 0, failedAttempts = 3, nextAllowedAtMs = now - 1),
            ),
        )
    }

    @Test
    fun `nextAllowedAt pushes the window forward by the exponential backoff`() {
        val now = 1_000_000L
        assertEquals(now, RecShelfRefreshPolicy.nextAllowedAt(now, 0))
        assertEquals(now + base, RecShelfRefreshPolicy.nextAllowedAt(now, 1))
        assertEquals(now + base * 2, RecShelfRefreshPolicy.nextAllowedAt(now, 2))
    }

    @Test
    fun `a DiscoverHit round-trips through the cached projection - incl the arXiv id`() {
        val hit =
            DiscoverHit(
                s2PaperId = "s2-1",
                title = "T",
                authors = listOf("A", "B"),
                year = 2026,
                venue = "NeurIPS",
                abstract = "x",
                arxivId = dev.blokz.arxiver.core.model.ArxivId("2606.11111"),
                doi = "10.1/x",
                openAccessPdfUrl = "https://pdf",
            )
        val round = hit.toCached().toHit()
        assertEquals(hit.s2PaperId, round.s2PaperId)
        assertEquals(hit.arxivId?.value, round.arxivId?.value)
        assertEquals(hit.authors, round.authors)
        assertEquals(hit.doi, round.doi)
        assertEquals(hit.openAccessPdfUrl, round.openAccessPdfUrl)
        // A DOI-only hit keeps a null arXiv id through the round-trip.
        assertEquals(null, hit.copy(arxivId = null).toCached().toHit().arxivId)
    }
}
