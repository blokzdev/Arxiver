package dev.blokz.arxiver.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.blokz.arxiver.core.database.entity.RoutineConfigEntity
import dev.blokz.arxiver.core.database.entity.RoutineDispatchEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineDao {
    // --- configs ---

    @Insert
    suspend fun insertConfig(config: RoutineConfigEntity): Long

    @Update
    suspend fun updateConfig(config: RoutineConfigEntity)

    @Query("DELETE FROM routine_configs WHERE id = :id")
    suspend fun deleteConfig(id: Long)

    @Query("SELECT * FROM routine_configs ORDER BY name")
    fun observeConfigs(): Flow<List<RoutineConfigEntity>>

    @Query("SELECT * FROM routine_configs WHERE id = :id")
    suspend fun configById(id: Long): RoutineConfigEntity?

    @Query("UPDATE routine_configs SET last_used_at = :usedAt, auth_invalid = 0 WHERE id = :id")
    suspend fun markUsed(
        id: Long,
        usedAt: Long,
    )

    @Query("UPDATE routine_configs SET auth_invalid = 1 WHERE id = :id")
    suspend fun markAuthInvalid(id: Long)

    // --- dispatches ---

    @Insert
    suspend fun insertDispatch(dispatch: RoutineDispatchEntity): Long

    @Query("SELECT * FROM routine_dispatches WHERE id = :id")
    suspend fun dispatchById(id: Long): RoutineDispatchEntity?

    @Query(
        "UPDATE routine_dispatches SET status = :status, http_code = :httpCode, error = :error, sent_at = :sentAt " +
            "WHERE id = :id",
    )
    suspend fun updateDispatchStatus(
        id: Long,
        status: String,
        httpCode: Int?,
        error: String?,
        sentAt: Long?,
    )

    @Query("SELECT * FROM routine_dispatches ORDER BY created_at DESC LIMIT 200")
    fun observeDispatches(): Flow<List<RoutineDispatchEntity>>

    @Query("SELECT * FROM routine_dispatches WHERE status = 'queued' ORDER BY created_at")
    suspend fun queuedDispatches(): List<RoutineDispatchEntity>

    @Query("DELETE FROM routine_dispatches WHERE id = :id")
    suspend fun deleteDispatch(id: Long)

    /** SPEC-CLAUDE-BRIDGE §6: 90-day history retention. */
    @Query("DELETE FROM routine_dispatches WHERE created_at < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)
}
