package dev.blokz.arxiver.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import dev.blokz.arxiver.core.database.entity.PaperEntity

data class PaperFtsMatch(
    val paperId: String,
    val matchinfo: ByteArray,
)

data class NoteFtsMatch(
    val paperId: String,
    val matchinfo: ByteArray,
)

@Dao
interface SearchDao {
    @Query(
        """
        SELECT p.id AS paperId, matchinfo(papers_fts, 'pcnalx') AS matchinfo
        FROM papers_fts
        JOIN papers p ON p.rowid = papers_fts.rowid
        WHERE papers_fts MATCH :match
        LIMIT :limit
        """,
    )
    suspend fun matchPapers(
        match: String,
        limit: Int = 200,
    ): List<PaperFtsMatch>

    @Query(
        """
        SELECT n.paper_id AS paperId, matchinfo(notes_fts, 'pcnalx') AS matchinfo
        FROM notes_fts
        JOIN notes n ON n.id = notes_fts.rowid
        WHERE notes_fts MATCH :match
        LIMIT :limit
        """,
    )
    suspend fun matchNotes(
        match: String,
        limit: Int = 200,
    ): List<NoteFtsMatch>

    @Query("SELECT * FROM papers WHERE id IN (:ids)")
    suspend fun papersByIds(ids: List<String>): List<PaperEntity>
}
