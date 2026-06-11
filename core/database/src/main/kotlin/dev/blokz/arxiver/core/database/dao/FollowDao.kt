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

    @Query("DELETE FROM follows WHERE type = :type AND value = :value")
    suspend fun delete(
        type: String,
        value: String,
    )

    @Query("SELECT * FROM follows WHERE enabled = 1")
    suspend fun enabledFollows(): List<FollowEntity>

    @Query("SELECT * FROM follows")
    fun observeAll(): Flow<List<FollowEntity>>

    @Query("SELECT value FROM follows WHERE type = 'category'")
    fun observeFollowedCategoryCodes(): Flow<List<String>>

    @Query("UPDATE follows SET last_synced_at = :syncedAt WHERE id = :id")
    suspend fun markSynced(
        id: Long,
        syncedAt: Long,
    )
}
