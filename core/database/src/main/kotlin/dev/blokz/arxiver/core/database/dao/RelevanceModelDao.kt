package dev.blokz.arxiver.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.blokz.arxiver.core.database.entity.RelevanceModelEntity

/**
 * The single-row relevance-model store (P5.3). The single-row invariant is enforced HERE — [upsert] pins
 * `id = 0` and REPLACEs — because Room can neither express nor schema-validate a SQL CHECK for it.
 */
@Dao
interface RelevanceModelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: RelevanceModelEntity)

    /** The one row, or null = no model fitted yet (≡ the legacy 0.55 constant). */
    @Query("SELECT * FROM relevance_model WHERE id = 0")
    suspend fun current(): RelevanceModelEntity?

    /** Live view for the UI threshold — Today reacts when a worker pass fits a new calibration. */
    @Query("SELECT * FROM relevance_model WHERE id = 0")
    fun observe(): kotlinx.coroutines.flow.Flow<RelevanceModelEntity?>

    /** Pin the invariant: whatever id the caller built, the stored row is id 0. */
    suspend fun upsert(model: RelevanceModelEntity) = insert(model.copy(id = 0))

    /** Stale-embedder discard (mirrors the vector-tag guard): a model fit under another embedding never applies. */
    @Query("DELETE FROM relevance_model WHERE embedding_model != :model")
    suspend fun deleteByModelMismatch(model: String)
}
