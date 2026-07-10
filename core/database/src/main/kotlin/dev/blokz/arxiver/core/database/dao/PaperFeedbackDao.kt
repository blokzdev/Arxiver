package dev.blokz.arxiver.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.blokz.arxiver.core.database.entity.PaperFeedbackEntity

/**
 * Durable relevance labels for the two-sided inbox ranker (P4). Reads feed
 * `EmbeddingWorker.scoreInbox` (positives ∪ negatives); writes come from inbox dismiss (revealed)
 * and the explicit thumbs control (P4.2).
 */
@Dao
interface PaperFeedbackDao {
    /** Upsert: a newer label for a paper replaces the older (REPLACE on the `paper_id` PK). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(feedback: PaperFeedbackEntity)

    /** Papers labelled negative (dismiss or thumb-down) — the ranker's push-away set. */
    @Query("SELECT paper_id FROM paper_feedback WHERE signal < 0")
    suspend fun negativePaperIds(): List<String>

    /** Papers labelled positive (thumb-up) — augments the library positives. */
    @Query("SELECT paper_id FROM paper_feedback WHERE signal > 0")
    suspend fun positivePaperIds(): List<String>

    /** Current vote for a paper (+1 / -1), or null if unlabelled — drives the row's thumb state. */
    @Query("SELECT signal FROM paper_feedback WHERE paper_id = :paperId")
    suspend fun voteFor(paperId: String): Int?

    /** Clear a paper's label (thumb undo, P4.2). */
    @Query("DELETE FROM paper_feedback WHERE paper_id = :paperId")
    suspend fun clear(paperId: String)

    /**
     * Every labeled example for the offline ranker eval (P5.1), with its current-model vector and the
     * title-only segment flag. **UNIONs library saves as positives** — the original P5 plan read only
     * `paper_feedback`, but saves (the ranker's dominant positive, `InboxScorer`) never write a feedback row,
     * so a feedback-only read reports "insufficient data" forever.
     *
     * Contracts baked into the SQL, mirrored from the live scorer:
     * - joined to `paper_embeddings` on the **injected** [modelTag] — a stale-model vector never reaches a fold;
     * - `p.embedded_at IS NOT NULL` — a re-synced paper whose abstract arrived AFTER embedding would otherwise
     *   flip segments while its old title-only vector persists (the segment key must describe the vector);
     * - the scorer's dedupe rule (a saved-or-thumbed paper is never also a negative) via the LEFT JOIN guard:
     *   a save with a *negative* feedback row keeps the positive (positive wins), matching `InboxScorer:40`;
     * - `title_only` = the paper's stored abstract is blank (the P-Explorer census axis).
     */
    @Query(
        """
        SELECT f.paper_id AS paperId, e.vector AS vector, (f.signal > 0) AS positive,
               f.source AS labelSource, f.created_at AS createdAt,
               (p.abstract = '') AS titleOnly
        FROM paper_feedback f
        JOIN paper_embeddings e ON e.paper_id = f.paper_id AND e.model = :modelTag
        JOIN papers p ON p.id = f.paper_id AND p.embedded_at IS NOT NULL
        WHERE f.signal > 0 OR f.paper_id NOT IN (SELECT paper_id FROM library_entries)
        UNION ALL
        SELECT l.paper_id, e.vector, 1, 'save', l.added_at, (p.abstract = '')
        FROM library_entries l
        JOIN paper_embeddings e ON e.paper_id = l.paper_id AND e.model = :modelTag
        JOIN papers p ON p.id = l.paper_id AND p.embedded_at IS NOT NULL
        WHERE l.paper_id NOT IN (SELECT paper_id FROM paper_feedback WHERE signal > 0)
        """,
    )
    suspend fun labeledExamples(modelTag: String): List<LabeledExampleRow>
}

/**
 * One eval label (P5.1). [labelSource] disambiguates the PU weight downstream (`save`/`thumb` = full evidence;
 * `dismiss` = a weak "not now"). [positive] arrives as SQLite's 0/1.
 */
data class LabeledExampleRow(
    val paperId: String,
    val vector: ByteArray,
    val positive: Boolean,
    val labelSource: String,
    val createdAt: Long,
    val titleOnly: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is LabeledExampleRow && other.paperId == paperId

    override fun hashCode(): Int = paperId.hashCode()
}
