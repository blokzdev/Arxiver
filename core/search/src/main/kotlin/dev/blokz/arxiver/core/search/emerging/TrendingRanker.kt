package dev.blokz.arxiver.core.search.emerging

import java.time.Instant
import kotlin.math.ln

/**
 * The pure "Emerging in your areas" engine (P-Discover2 PD.3) — no Android, no Room, no IO, injected clock, fully
 * deterministic and CI-testable (mirrors the pure `KMeans`). It ranks arXiv CATEGORIES (a controlled vocabulary that
 * can't lie — "cs.RO" is always a true label, unlike a fabricated free-text "topic") that are more active than usual
 * in the user's OWN recent follow feed, measured against a trailing baseline.
 *
 * Honesty is structural: the input carries only [TrendingWindowPaper.publishedAt] (no sync clock, no resubmission
 * date), emergence is RELATIVE across the user's own categories (a global conference-deadline surge lifts all of them
 * together → none stands out → self-cancels), and a per-follow warmup + author-diversity + min-volume floors keep a
 * single backfill/author from minting a trend. The caller renders only [EmergingArea.confirmed] areas and never adds
 * counts/badges/notifications.
 */
class TrendingRanker(
    private val config: TrendingConfig = TrendingConfig(),
) {
    /**
     * @param papers already scoped by the caller to active-inbox, enabled arXiv follows, within the baseline window.
     * @param now injected clock — no wall-clock read inside.
     * @param previousDayKeys yesterday's surfaced categories; an area is [EmergingArea.confirmed] only if it persists.
     */
    fun rank(
        papers: List<TrendingWindowPaper>,
        now: Instant,
        previousDayKeys: Set<String> = emptySet(),
    ): List<EmergingArea> {
        val nowMs = now.toEpochMilli()
        val recentFrom = nowMs - config.recentWindowDays * DAY_MS
        val baselineFrom = recentFrom - config.baselineWindowDays * DAY_MS

        // Warmup: a follow participates only if it has real baseline history — a first-sync backfill (all mass in the
        // recent window, nothing in the baseline) is cold and excluded, so a brand-new follow can't instantly "emerge".
        val baselineCountByFollow =
            papers.filter { it.publishedAt in baselineFrom until recentFrom }
                .groupingBy { it.followId }
                .eachCount()
        val warmFollows = baselineCountByFollow.filterValues { it >= config.minFollowBaselinePapers }.keys
        val warm = papers.filter { it.followId in warmFollows && it.publishedAt in baselineFrom..nowMs }
        if (warm.isEmpty()) return emptyList()

        val recent = warm.filter { it.publishedAt >= recentFrom }
        val baseline = warm.filter { it.publishedAt < recentFrom }
        val totalRecentDocs = recent.map { it.paperId }.distinct().size
        val totalBaselineDocs = baseline.map { it.paperId }.distinct().size

        // Corpus gates: need a real baseline AND a multi-category frame (below 3 categories a global deadline can't
        // self-cancel — R1 + R3 hit at once with nothing to offset them).
        val distinctCategories = warm.flatMap { it.categories }.distinct()
        if (totalBaselineDocs < config.minBaselineDocs) return emptyList()
        if (distinctCategories.size < config.minDistinctFollowedCategories) return emptyList()
        if (totalRecentDocs == 0) return emptyList()

        val categoryCount = distinctCategories.size
        val areas =
            recent.flatMap { it.categories }.distinct().mapNotNull { category ->
                val recentForCategory = recent.filter { category in it.categories }
                val recDocs = recentForCategory.map { it.paperId }.distinct().size
                if (recDocs < config.minAreaRecentDocs) return@mapNotNull null
                val recAuthors = recentForCategory.flatMap(::authorsOf).distinct().size
                if (recAuthors < config.minAreaAuthors) return@mapNotNull null
                val baseDocs = baseline.filter { category in it.categories }.map { it.paperId }.distinct().size
                if (baseDocs < config.minAreaBaselineDocs) return@mapNotNull null

                val recRate = recDocs.toDouble() / totalRecentDocs
                val baseRate = (baseDocs + config.smoothingK) / (totalBaselineDocs + config.smoothingK * categoryCount)
                val lift = recRate / baseRate
                if (lift < config.minLift) return@mapNotNull null

                // score rewards breadth of support (recDocs), not a raw spike, so a big lift on tiny volume ranks low.
                EmergingArea(
                    category = category,
                    score = ln(lift) * ln(1.0 + recDocs),
                    lift = lift,
                    recentDocs = recDocs,
                    drivingPaperIds =
                        recentForCategory.sortedByDescending { it.publishedAt }
                            .map { it.paperId }
                            .distinct()
                            .take(config.maxDrivingPapers),
                    confirmed = category in previousDayKeys,
                )
            }

        return areas
            .sortedWith(
                compareByDescending<EmergingArea> { it.score }
                    .thenByDescending { it.recentDocs }
                    .thenBy { it.category },
            )
            .take(config.topK)
    }

    private fun authorsOf(paper: TrendingWindowPaper): List<String> =
        paper.authorsLine.split(", ").map { it.trim() }.filter { it.isNotBlank() }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
