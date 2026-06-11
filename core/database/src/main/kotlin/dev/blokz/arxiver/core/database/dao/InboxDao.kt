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
)

@Dao
interface InboxDao {
    /** IGNORE: a paper already triaged must not reappear as new on re-sync. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<InboxItemEntity>)

    @Query(
        """
        SELECT p.*, i.arrived_at, i.state, i.score FROM papers p
        JOIN inbox_items i ON i.paper_id = p.id
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
}
