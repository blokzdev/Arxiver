package dev.blokz.arxiver.core.search.emerging

/**
 * All thresholds/windows for [TrendingRanker] (P-Discover2 PD.3), defaulted. Pure data — the engine is deterministic
 * given a config + input, so tests pin behavior by construction.
 */
data class TrendingConfig(
    /** The "recent" window: papers published in the last [recentWindowDays] days. */
    val recentWindowDays: Long = 14,
    /** The trailing baseline spans the [baselineWindowDays] days BEFORE the recent window (so a prior deadline sits in it). */
    val baselineWindowDays: Long = 56,
    /** An area must have at least this many distinct recent papers (low-volume noise floor). */
    val minAreaRecentDocs: Int = 5,
    /** …and this many baseline papers — you can't honestly say "more than usual" without a usual. */
    val minAreaBaselineDocs: Int = 3,
    /** …and this many DISTINCT authors across its recent papers (one prolific lab can't mint a trend). */
    val minAreaAuthors: Int = 3,
    /** Recent rate must exceed baseline rate by at least this factor (the primary emergence knob). */
    val minLift: Double = 1.5,
    /** Additive smoothing so one brand-new category can't score an unbounded lift. */
    val smoothingK: Double = 1.0,
    /** A follow participates only once it has this much baseline history (kills the first-sync backfill spike). */
    val minFollowBaselinePapers: Int = 5,
    /** No shelf at all until the whole baseline has this many papers. */
    val minBaselineDocs: Int = 30,
    /** No shelf until the user's feed spans this many categories — the relative frame that self-cancels a global deadline. */
    val minDistinctFollowedCategories: Int = 3,
    /** Surface at most this many areas. */
    val topK: Int = 3,
    /** Show at most this many driving papers per area. */
    val maxDrivingPapers: Int = 3,
)

/**
 * One paper in the emergence window (P-Discover2 PD.3). **Honest-clock invariant, compile-enforced:** there is no
 * `arrivedAt` and no `updatedAt` field, so the ranker CANNOT key on the sync clock (a dark pattern — the sync stamps
 * papers with no user present) or on resubmission dates (a v2/v3 bump would masquerade as emergence). [publishedAt]
 * is the only time signal. [categories] is the paper's full arXiv category set (primary + cross-lists) so a rising
 * cross-list area — "Robotics picking up in your ML feed" — is detectable, not just the coarse primary.
 */
data class TrendingWindowPaper(
    val paperId: String,
    val categories: List<String>,
    val publishedAt: Long,
    val authorsLine: String,
    val followId: Long,
    val followCreatedAt: Long,
)

/**
 * An arXiv category that is genuinely more active than usual in the user's own recent feed (P-Discover2 PD.3).
 * [category] is the raw arXiv code (the UI maps it to a human name); [confirmed] is the 2-day-persistence flag the UI
 * gates on for calm. Never a "topic" or a popularity claim — only "more active than usual in YOUR follows".
 */
data class EmergingArea(
    val category: String,
    val score: Double,
    val lift: Double,
    val recentDocs: Int,
    val drivingPaperIds: List<String>,
    val confirmed: Boolean,
)
