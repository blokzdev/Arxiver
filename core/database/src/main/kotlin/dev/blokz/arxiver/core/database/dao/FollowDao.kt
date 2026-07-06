package dev.blokz.arxiver.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.blokz.arxiver.core.database.entity.FollowEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FollowDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(follow: FollowEntity): Long

    // Origin-scoped (P-Feeds PF.3): the unique index is (type, value, origin), so an unfollow MUST name the
    // origin — else it would delete the same (type, value) followed on every other source too.
    @Query("DELETE FROM follows WHERE type = :type AND value = :value AND origin = :origin")
    suspend fun delete(
        type: String,
        value: String,
        origin: String,
    )

    @Query("SELECT * FROM follows WHERE enabled = 1")
    suspend fun enabledFollows(): List<FollowEntity>

    /** The single follow matching (type, value, origin), or null — used to resolve its id for inbox cleanup (PF.3). */
    @Query("SELECT * FROM follows WHERE type = :type AND value = :value AND origin = :origin LIMIT 1")
    suspend fun find(
        type: String,
        value: String,
        origin: String,
    ): FollowEntity?

    @Query("SELECT * FROM follows")
    fun observeAll(): Flow<List<FollowEntity>>

    // arXiv-scoped: this feeds the arXiv taxonomy grid's follow Booleans, so a non-arXiv follow of the same
    // category code (e.g. bioRxiv `neuroscience`) must NOT light up the arXiv grid row. The picker observes
    // origin via [observeAll] instead. (PF.3)
    @Query("SELECT value FROM follows WHERE type = 'category' AND origin = 'arxiv'")
    fun observeFollowedCategoryCodes(): Flow<List<String>>

    /** Origin-agnostic enabled-follow count — Today's "has any follows" signal (PF.3; never the arXiv-scoped grid). */
    @Query("SELECT COUNT(*) FROM follows WHERE enabled = 1")
    fun observeEnabledFollowCount(): Flow<Int>

    @Query("UPDATE follows SET last_synced_at = :syncedAt WHERE id = :id")
    suspend fun markSynced(
        id: Long,
        syncedAt: Long,
    )
}
