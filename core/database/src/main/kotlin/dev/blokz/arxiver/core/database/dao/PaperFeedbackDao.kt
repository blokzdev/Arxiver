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
}
