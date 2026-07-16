package dev.blokz.arxiver.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.blokz.arxiver.core.database.entity.InboxItemEntity
import dev.blokz.arxiver.core.database.entity.PaperEntity
import kotlinx.coroutines.flow.Flow

data class InboxRow(
    @Embedded val paper: PaperEntity,
    val arrived_at: Long,
    val state: String,
    val score: Double?,
    val vote: Int?,
)

@Dao
interface InboxDao {
    /** IGNORE: a paper already triaged must not reappear as new on re-sync. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<InboxItemEntity>)

    @Query(
        """
        SELECT p.*, i.arrived_at, i.state, i.score, f.signal AS vote FROM papers p
        JOIN inbox_items i ON i.paper_id = p.id
        LEFT JOIN paper_feedback f ON f.paper_id = p.id AND f.source = 'thumb'
        WHERE i.state IN ('new', 'seen')
        ORDER BY i.score IS NULL, i.score DESC, i.arrived_at DESC
        """,
    )
    fun observeActiveInbox(): Flow<List<InboxRow>>

    @Query("UPDATE inbox_items SET state = :state WHERE paper_id = :paperId")
    suspend fun setState(
        paperId: String,
        state: String,
    )

    @Query("SELECT paper_id FROM inbox_items WHERE state IN ('new', 'seen')")
    suspend fun activePaperIds(): List<String>

    /**
     * Active-inbox paper ids surfaced by *enabled* follows — the P4 cold-start interest seed (SPEC-SEARCH §5).
     * Joins the recorded origin follow so papers left behind by a since-disabled follow don't seed the model.
     */
    @Query(
        """
        SELECT i.paper_id FROM inbox_items i
        JOIN follows f ON f.id = i.follow_id
        WHERE i.state IN ('new', 'seen') AND f.enabled = 1
        """,
    )
    suspend fun activeIdsFromEnabledFollows(): List<String>

    /** The paper's current inbox score — captured into `paper_feedback.score_at_label` at label time (P5.3). */
    @Query("SELECT score FROM inbox_items WHERE paper_id = :paperId")
    suspend fun scoreFor(paperId: String): Double?

    @Query("UPDATE inbox_items SET score = :score WHERE paper_id = :paperId")
    suspend fun setScore(
        paperId: String,
        score: Double?,
    )

    @Query("SELECT COUNT(*) FROM inbox_items WHERE state = 'new'")
    fun observeNewCount(): Flow<Int>

    /** Dismissed rows older than [cutoff] are pruned (SPEC-DATA §2). */
    @Query("DELETE FROM inbox_items WHERE state = 'dismissed' AND arrived_at < :cutoff")
    suspend fun pruneDismissed(cutoff: Long)

    /**
     * Scored active-inbox rows with the title-only segment flag (P5.1) — feeds the label-free per-segment
     * score-distribution tripwire (a systematically-lower-scoring title-only population would silently starve
     * below the "Likely relevant" cut with zero labels to prove it).
     */
    @Query(
        """
        SELECT i.score AS score, (p.abstract = '') AS titleOnly FROM inbox_items i
        JOIN papers p ON p.id = i.paper_id AND p.embedded_at IS NOT NULL
        WHERE i.state IN ('new', 'seen') AND i.score IS NOT NULL
        """,
    )
    suspend fun activeScoresBySegment(): List<ScoredSegmentRow>

    /**
     * Delete the inbox rows a follow put here — called in the same operation as unfollow (P-Feeds PF.3), so an
     * unfollowed source's rows don't dangle on a dead `follow_id`. Inbox PK is `paper_id` alone (a paper appears
     * once regardless of how many follows surfaced it), so this removes rows whose *recorded* origin follow is
     * being deleted; a paper still followed via another enabled follow is re-inboxed on the next sync.
     */
    @Query("DELETE FROM inbox_items WHERE follow_id = :followId")
    suspend fun deleteByFollowId(followId: Long)

    /**
     * Rows eligible for an ambient digest (P-Ambient PA.1b): active + scored + at/above the calibrated cut +
     * NOT yet digested + a recent arrival (so a weeks-old row that only now crosses a shifted cut isn't
     * announced as "new"). Ordered best-first so the notifier can take the top-N titles. `score IS NOT NULL`
     * excludes cold-start/recency rows (never "N likely-relevant" from unscored papers).
     */
    @Query(
        """
        SELECT i.paper_id AS paperId, p.title AS title FROM inbox_items i
        JOIN papers p ON p.id = i.paper_id
        WHERE i.state IN ('new', 'seen') AND i.score IS NOT NULL AND i.score >= :cut
              AND i.digested_at IS NULL AND i.arrived_at > :recencyFloor
        ORDER BY i.score DESC
        """,
    )
    suspend fun eligibleDigest(
        cut: Double,
        recencyFloor: Long,
    ): List<DigestRow>

    /**
     * The current top-[k] likely-relevant rows for the home-screen widget (P-Ambient PA.2): active + scored +
     * at/above the SAME calibrated cut the digest + Today use ([RelevanceThreshold.cut]). Unlike [eligibleDigest]
     * this drops both the `digested_at IS NULL` filter (the widget shows the *current best*, not "newly announced"
     * ones) and the recency floor (a widget is a standing surface, not a one-shot alert). `score IS NOT NULL`
     * keeps cold-start/unscored papers off it (never a fake "likely relevant"). `paper_id` is the deep-link id.
     */
    @Query(
        """
        SELECT i.paper_id AS paperId, p.title AS title FROM inbox_items i
        JOIN papers p ON p.id = i.paper_id
        WHERE i.state IN ('new', 'seen') AND i.score IS NOT NULL AND i.score >= :cut
        ORDER BY i.score DESC LIMIT :k
        """,
    )
    suspend fun activeInboxTopK(
        cut: Double,
        k: Int,
    ): List<DigestRow>

    /**
     * Stamp `digested_at` on exactly the counted rows, in one statement, BEFORE the notification posts
     * (crash-safe: at worst one digest is lost, never double-fired). `digested_at IS NULL` keeps it idempotent
     * even if the id set overlaps a concurrent write.
     */
    @Query("UPDATE inbox_items SET digested_at = :now WHERE paper_id IN (:paperIds) AND digested_at IS NULL")
    suspend fun markDigested(
        paperIds: List<String>,
        now: Long,
    )

    /**
     * The active-inbox window feeding the "Emerging in your areas" engine (P-Discover2 PD.3b). One row per
     * (paper, arXiv category) — the [PaperCategoryCrossRef] join is what lets a rising CROSS-LIST area surface, not
     * just the coarse primary. Scoped to enabled follows + `origin='arxiv'` (a non-arXiv `published_at` is a re-harvest
     * artifact) + a `published_at` window (never `arrived_at`/`updated_at` — the honest clock). Read once/day off-main.
     */
    @Query(
        """
        SELECT p.id AS paperId, pc.category_code AS categoryCode, p.published_at AS publishedAt,
               p.authors_line AS authorsLine, i.follow_id AS followId, f.created_at AS followCreatedAt
        FROM inbox_items i
        JOIN follows f ON f.id = i.follow_id
        JOIN papers p ON p.id = i.paper_id
        JOIN paper_categories pc ON pc.paper_id = p.id
        WHERE i.state IN ('new', 'seen') AND f.enabled = 1 AND p.origin = 'arxiv'
              AND p.published_at >= :baselineFrom
        """,
    )
    suspend fun trendingWindowRows(baselineFrom: Long): List<TrendingWindowRow>
}

/** One (paper, arXiv category) row in the emergence window (PD.3b) — grouped into a `TrendingWindowPaper` by the repo. */
data class TrendingWindowRow(
    val paperId: String,
    val categoryCode: String,
    val publishedAt: Long,
    val authorsLine: String,
    val followId: Long,
    val followCreatedAt: Long,
)

/** One active-inbox score + its embedding-quality segment (P5.1 tripwire). */
data class ScoredSegmentRow(
    val score: Double,
    val titleOnly: Boolean,
)

/** A paper eligible for the ambient digest — id (for the deep-link) + title (for the body). PA.1b. */
data class DigestRow(
    val paperId: String,
    val title: String,
)
