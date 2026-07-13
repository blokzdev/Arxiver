package dev.blokz.arxiver.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.blokz.arxiver.core.database.entity.ChunkEmbeddingEntity
import kotlinx.coroutines.flow.Flow

/** A chunk FTS hit: the chunk rowid plus its raw `matchinfo` blob for BM25. */
data class ChunkFtsMatch(
    val chunkId: Long,
    val matchinfo: ByteArray,
)

/** A corpus-wide body-chunk FTS hit: the owning paper + its `matchinfo` blob for BM25 (P-FullText PFT.3). */
data class ChunkFtsBodyMatch(
    val paperId: String,
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

    /**
     * Delete only the named [sourceKinds] for a paper (P-FullText PFT.1). The chunk store is multi-source
     * (`abstract`/`note`/`body`); a paper-wide delete would let one source's re-index clobber another's rows,
     * so every delete is source-scoped. Paper deletion itself cascades via the `paper_id` FK, not this.
     */
    @Query("DELETE FROM chunk_embeddings WHERE paper_id = :paperId AND source_kind IN (:sourceKinds)")
    suspend fun deleteForPaperSources(
        paperId: String,
        sourceKinds: List<String>,
    )

    /**
     * Atomically replace exactly the named [sourceKinds] for [paperId] (P-FullText PFT.1): a source-scoped
     * delete + insert in ONE transaction, so a concurrent reader never sees the paper mid-reindex with zero
     * rows, and a body re-index can never tear an abstract/note re-index (or vice-versa).
     */
    @Transaction
    suspend fun replacePaperSources(
        paperId: String,
        sourceKinds: List<String>,
        rows: List<ChunkEmbeddingEntity>,
    ) {
        deleteForPaperSources(paperId, sourceKinds)
        insert(rows)
    }

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

    /** Distinct papers with at least one current-model **body** chunk — the full-text coverage count (PFT.3). */
    @Query("SELECT COUNT(DISTINCT paper_id) FROM chunk_embeddings WHERE model = :model AND source_kind = 'body'")
    fun observeBodyIndexedPaperCount(model: String): Flow<Int>

    // --- backfill (eager library indexing) ---

    /**
     * Library papers with no current-model **abstract** chunk yet, most-recently-added first (the eager
     * abstract/note RAG backfill). Scoped to `source_kind='abstract'` (P-FullText PFT.1): a paper that
     * already has a `body` chunk must STILL be abstract-indexed — an unscoped `EXISTS` would let a body-only
     * paper masquerade as fully indexed and silently starve it of abstract/note chunks. Body backfill is a
     * separate, filesystem-driven pass (PFT.2).
     */
    @Query(
        """
        SELECT le.paper_id FROM library_entries le
        WHERE NOT EXISTS (
            SELECT 1 FROM chunk_embeddings ce
            WHERE ce.paper_id = le.paper_id AND ce.model = :model AND ce.source_kind = 'abstract'
        )
        ORDER BY le.added_at DESC
        LIMIT :limit
        """,
    )
    suspend fun libraryPapersMissingChunks(
        model: String,
        limit: Int,
    ): List<String>

    /**
     * A collection's papers with no current-model **abstract** chunk yet (ensure-embedded on chat open).
     * Scoped to `source_kind='abstract'` (P-FullText PFT.1) for the same anti-starvation reason as
     * [libraryPapersMissingChunks] — a body-indexed paper still needs its abstract/note chunks.
     */
    @Query(
        """
        SELECT cp.paper_id FROM collection_papers cp
        WHERE cp.collection_id = :collectionId AND NOT EXISTS (
            SELECT 1 FROM chunk_embeddings ce
            WHERE ce.paper_id = cp.paper_id AND ce.model = :model AND ce.source_kind = 'abstract'
        )
        ORDER BY cp.added_at DESC
        LIMIT :limit
        """,
    )
    suspend fun collectionPapersMissingChunks(
        collectionId: Long,
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

    /**
     * Corpus-wide body-chunk FTS match (P-FullText PFT.3) — the "Also found in full text" leg. Unscoped
     * `chunk_fts MATCH` restricted to `source_kind='body'`, returning one row per matching body chunk with its
     * owning paper. The caller (`CorpusBodyRetriever`) MAX-rolls-up BM25 per paper. `LIMIT` is a DoS backstop,
     * NOT relevance truncation: v1's reader-opened subset is small, so all matches are scored; at large
     * coverage this must be candidate-gated first (SPEC-SEARCH note + PFT.5). Runs OFF the traced main-search
     * path, so it never inflates the D2 latency budget.
     */
    @Query(
        """
        SELECT ce.paper_id AS paperId, matchinfo(chunk_fts, 'pcnalx') AS matchinfo
        FROM chunk_fts
        JOIN chunk_embeddings ce ON ce.id = chunk_fts.rowid
        WHERE chunk_fts MATCH :match AND ce.source_kind = 'body'
        LIMIT :limit
        """,
    )
    suspend fun matchBodyChunks(
        match: String,
        limit: Int = 2000,
    ): List<ChunkFtsBodyMatch>
}
