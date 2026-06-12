package dev.blokz.arxiver.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.database.entity.RelatedPaperEntity
import kotlinx.coroutines.flow.Flow

data class RelatedRow(
    @Embedded val paper: PaperEntity,
    val similarity: Double,
)

data class NeighborRow(
    @Embedded val paper: PaperEntity,
    val similarity: Double,
    val in_library: Boolean,
)

@Dao
interface EmbeddingDao {
    @Upsert
    suspend fun upsert(embedding: PaperEmbeddingEntity)

    @Query("SELECT * FROM paper_embeddings LIMIT :limit OFFSET :offset")
    suspend fun chunk(
        limit: Int,
        offset: Int,
    ): List<PaperEmbeddingEntity>

    @Query("SELECT * FROM paper_embeddings WHERE paper_id = :paperId")
    suspend fun byPaperId(paperId: String): PaperEmbeddingEntity?

    @Query("SELECT COUNT(*) FROM paper_embeddings")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM paper_embeddings")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM paper_embeddings")
    suspend fun deleteAll()

    /** Library and inbox papers first — the corpus that matters most. */
    @Query(
        """
        SELECT p.* FROM papers p
        WHERE p.embedded_at IS NULL AND p.source != 'S2_STUB'
        ORDER BY
            (SELECT COUNT(*) FROM library_entries le WHERE le.paper_id = p.id) DESC,
            (SELECT COUNT(*) FROM inbox_items i WHERE i.paper_id = p.id) DESC,
            p.fetched_at DESC
        LIMIT :limit
        """,
    )
    suspend fun unembeddedPapers(limit: Int): List<PaperEntity>

    @Query("UPDATE papers SET embedded_at = :embeddedAt WHERE id = :paperId")
    suspend fun markEmbedded(
        paperId: String,
        embeddedAt: Long,
    )

    @Query("UPDATE papers SET embedded_at = NULL")
    suspend fun clearEmbeddedMarks()

    // --- related papers ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelated(related: List<RelatedPaperEntity>)

    @Query("DELETE FROM related_papers WHERE paper_id = :paperId")
    suspend fun clearRelatedFor(paperId: String)

    @Query(
        """
        SELECT p.*, r.similarity FROM related_papers r
        JOIN papers p ON p.id = r.neighbor_id
        WHERE r.paper_id = :paperId
        ORDER BY r.similarity DESC
        """,
    )
    fun observeRelated(paperId: String): Flow<List<RelatedRow>>

    /** Top precomputed neighbors with library membership — dispatch relations context. */
    @Query(
        """
        SELECT p.*, r.similarity,
            EXISTS(SELECT 1 FROM library_entries le WHERE le.paper_id = p.id) AS in_library
        FROM related_papers r
        JOIN papers p ON p.id = r.neighbor_id
        WHERE r.paper_id = :paperId
        ORDER BY r.similarity DESC
        LIMIT :limit
        """,
    )
    suspend fun neighborsFor(
        paperId: String,
        limit: Int,
    ): List<NeighborRow>
}
