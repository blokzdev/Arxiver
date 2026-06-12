package dev.blokz.arxiver.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.blokz.arxiver.core.database.entity.CitationEdgeEntity
import dev.blokz.arxiver.core.database.entity.PaperEntity
import kotlinx.coroutines.flow.Flow

data class ConnectionRow(
    @Embedded val paper: PaperEntity,
    val in_library: Boolean,
)

@Dao
interface CitationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEdges(edges: List<CitationEdgeEntity>)

    @Query("DELETE FROM citation_edges WHERE citing_id = :paperId OR cited_id = :paperId")
    suspend fun clearEdgesFor(paperId: String)

    /** Papers this paper cites (references). */
    @Query(
        """
        SELECT p.*, EXISTS(SELECT 1 FROM library_entries le WHERE le.paper_id = p.id) AS in_library
        FROM citation_edges e
        JOIN papers p ON p.id = e.cited_id
        WHERE e.citing_id = :paperId
        ORDER BY in_library DESC, p.citation_count IS NULL, p.citation_count DESC
        """,
    )
    fun observeReferences(paperId: String): Flow<List<ConnectionRow>>

    /** Papers citing this paper. */
    @Query(
        """
        SELECT p.*, EXISTS(SELECT 1 FROM library_entries le WHERE le.paper_id = p.id) AS in_library
        FROM citation_edges e
        JOIN papers p ON p.id = e.citing_id
        WHERE e.cited_id = :paperId
        ORDER BY in_library DESC, p.citation_count IS NULL, p.citation_count DESC
        """,
    )
    fun observeCitations(paperId: String): Flow<List<ConnectionRow>>

    /** Edges with both endpoints inside the id set — dispatch relations context. */
    @Query("SELECT * FROM citation_edges WHERE citing_id IN (:ids) AND cited_id IN (:ids)")
    suspend fun edgesAmong(ids: List<String>): List<CitationEdgeEntity>

    @Query("SELECT COUNT(*) FROM citation_edges WHERE citing_id = :paperId")
    fun observeReferenceCount(paperId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM citation_edges WHERE cited_id = :paperId")
    fun observeCitationEdgeCount(paperId: String): Flow<Int>

    @Query("UPDATE papers SET citation_count = :count, citations_synced_at = :syncedAt WHERE id = :paperId")
    suspend fun markCitationsSynced(
        paperId: String,
        count: Int?,
        syncedAt: Long,
    )

    /** Library papers due for a citation refresh, stalest first. */
    @Query(
        """
        SELECT p.* FROM papers p
        JOIN library_entries le ON le.paper_id = p.id
        WHERE p.citations_synced_at IS NULL OR p.citations_synced_at < :staleBefore
        ORDER BY p.citations_synced_at IS NOT NULL, p.citations_synced_at ASC
        LIMIT :limit
        """,
    )
    suspend fun papersDueForCitationSync(
        staleBefore: Long,
        limit: Int,
    ): List<PaperEntity>

    /** Stub row for an edge endpoint we haven't fetched (no overwrite of real rows). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStubPaper(paper: PaperEntity)
}
