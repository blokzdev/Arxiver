package dev.blokz.arxiver.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.blokz.arxiver.core.database.entity.ChunkEmbeddingEntity
import kotlinx.coroutines.flow.Flow

/** A chunk FTS hit: the chunk rowid plus its raw `matchinfo` blob for BM25. */
data class ChunkFtsMatch(
    val chunkId: Long,
    val matchinfo: ByteArray,
)

/**
 * Chunk-embedding store for on-device RAG retrieval (SPEC-SEARCH §8). Vector
 * reads are scoped to a single paper or a collection's papers and paginated so
 * the cosine scan bounds memory at any corpus size (mirrors [EmbeddingDao.chunk]
 * via [VectorIndex]). The keyword leg matches `chunk_fts` scoped the same way.
 */
@Dao
interface ChunkEmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chunks: List<ChunkEmbeddingEntity>)

    @Query("DELETE FROM chunk_embeddings WHERE paper_id = :paperId")
    suspend fun deleteForPaper(paperId: String)

    /** Model-guard wipe: drop chunks embedded by any other model (SPEC-SEARCH §6). */
    @Query("DELETE FROM chunk_embeddings WHERE model != :model")
    suspend fun deleteByModelMismatch(model: String)

    @Query("DELETE FROM chunk_embeddings")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM chunk_embeddings")
    suspend fun count(): Int

    /** Distinct papers with at least one current-model chunk — drives the progress count. */
    @Query("SELECT COUNT(DISTINCT paper_id) FROM chunk_embeddings WHERE model = :model")
    fun observeIndexedPaperCount(model: String): Flow<Int>

    // --- backfill (eager library indexing) ---

    /** Library papers with no current-model chunk row yet, most-recently-added first. */
    @Query(
        """
        SELECT le.paper_id FROM library_entries le
        WHERE NOT EXISTS (
            SELECT 1 FROM chunk_embeddings ce
            WHERE ce.paper_id = le.paper_id AND ce.model = :model
        )
        ORDER BY le.added_at DESC
        LIMIT :limit
        """,
    )
    suspend fun libraryPapersMissingChunks(
        model: String,
        limit: Int,
    ): List<String>

    // --- scoped vector reads (semantic leg) ---

    @Query("SELECT * FROM chunk_embeddings WHERE paper_id = :paperId LIMIT :limit OFFSET :offset")
    suspend fun chunksForPaper(
        paperId: String,
        limit: Int,
        offset: Int,
    ): List<ChunkEmbeddingEntity>

    @Query(
        """
        SELECT ce.* FROM chunk_embeddings ce
        JOIN collection_papers cp ON cp.paper_id = ce.paper_id
        WHERE cp.collection_id = :collectionId
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun chunksForCollection(
        collectionId: Long,
        limit: Int,
        offset: Int,
    ): List<ChunkEmbeddingEntity>

    // --- scoped keyword reads (FTS leg) ---

    @Query(
        """
        SELECT ce.id AS chunkId, matchinfo(chunk_fts, 'pcnalx') AS matchinfo
        FROM chunk_fts
        JOIN chunk_embeddings ce ON ce.id = chunk_fts.rowid
        WHERE chunk_fts MATCH :match AND ce.paper_id = :paperId
        LIMIT :limit
        """,
    )
    suspend fun matchChunksForPaper(
        match: String,
        paperId: String,
        limit: Int = 200,
    ): List<ChunkFtsMatch>

    @Query(
        """
        SELECT ce.id AS chunkId, matchinfo(chunk_fts, 'pcnalx') AS matchinfo
        FROM chunk_fts
        JOIN chunk_embeddings ce ON ce.id = chunk_fts.rowid
        WHERE chunk_fts MATCH :match
          AND ce.paper_id IN (SELECT paper_id FROM collection_papers WHERE collection_id = :collectionId)
        LIMIT :limit
        """,
    )
    suspend fun matchChunksForCollection(
        match: String,
        collectionId: Long,
        limit: Int = 200,
    ): List<ChunkFtsMatch>
}
