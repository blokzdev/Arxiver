package dev.blokz.arxiver.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.blokz.arxiver.core.database.entity.AuthorEntity
import dev.blokz.arxiver.core.database.entity.PaperAuthorCrossRef
import dev.blokz.arxiver.core.database.entity.PaperCategoryCrossRef
import dev.blokz.arxiver.core.database.entity.PaperEntity
import kotlinx.coroutines.flow.Flow

/** Read model: a paper with its ordered authors and category codes. */
data class PaperWithRelations(
    val paper: PaperEntity,
    val authors: List<String>,
    val categories: List<String>,
)

@Dao
interface PaperDao {
    // --- writes ---

    @Upsert
    suspend fun upsertPaper(paper: PaperEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAuthorIgnore(author: AuthorEntity): Long

    @Query("SELECT id FROM authors WHERE name = :name")
    suspend fun authorIdByName(name: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPaperAuthors(refs: List<PaperAuthorCrossRef>)

    @Query("DELETE FROM paper_authors WHERE paper_id = :paperId")
    suspend fun clearPaperAuthors(paperId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPaperCategories(refs: List<PaperCategoryCrossRef>)

    @Query("DELETE FROM paper_categories WHERE paper_id = :paperId")
    suspend fun clearPaperCategories(paperId: String)

    /**
     * Upserts a paper together with its author and category relations.
     * Author rows are deduped by unique name (SPEC-DATA §2).
     */
    @Transaction
    suspend fun upsertPaperWithRelations(
        paper: PaperEntity,
        authors: List<String>,
        categories: List<String>,
    ) {
        upsertPaper(paper)
        clearPaperAuthors(paper.id)
        val refs =
            authors.mapIndexed { index, name ->
                val id =
                    insertAuthorIgnore(AuthorEntity(name = name))
                        .takeIf { it != -1L } ?: requireNotNull(authorIdByName(name))
                PaperAuthorCrossRef(paperId = paper.id, authorId = id, position = index)
            }
        insertPaperAuthors(refs)
        clearPaperCategories(paper.id)
        insertPaperCategories(
            categories.map {
                PaperCategoryCrossRef(
                    paperId = paper.id,
                    categoryCode = it,
                    isPrimary = it == paper.primaryCategory,
                )
            },
        )
    }

    // --- reads ---

    @Query("SELECT * FROM papers WHERE id = :id")
    suspend fun paperById(id: String): PaperEntity?

    /**
     * The canonical stored id for a DOI (P-FeedPolish cross-source de-dup) — arXiv-origin preferred (a paper
     * that was also on arXiv keys under the bare arXiv id), case-insensitive. Lets a follow re-key a hit onto
     * an already-stored row that shares the DOI instead of forking a second row.
     */
    @Query("SELECT id FROM papers WHERE doi = :doi COLLATE NOCASE ORDER BY (origin = 'arxiv') DESC, id ASC LIMIT 1")
    suspend fun paperIdByDoi(doi: String): String?

    /** Cached papers in a category, newest first — the cache-first Browse feed (no network). */
    @Query(
        """
        SELECT p.* FROM papers p
        JOIN paper_categories pc ON pc.paper_id = p.id
        WHERE pc.category_code = :code
        ORDER BY p.published_at DESC
        LIMIT :limit
        """,
    )
    suspend fun papersByCategory(
        code: String,
        limit: Int,
    ): List<PaperEntity>

    @Query("SELECT * FROM papers WHERE id = :id")
    fun observePaperById(id: String): Flow<PaperEntity?>

    @Query(
        """
        SELECT a.name FROM authors a
        JOIN paper_authors pa ON pa.author_id = a.id
        WHERE pa.paper_id = :paperId
        ORDER BY pa.position
        """,
    )
    suspend fun authorNamesFor(paperId: String): List<String>

    @Query(
        """
        SELECT a.name FROM authors a
        JOIN paper_authors pa ON pa.author_id = a.id
        WHERE pa.paper_id = :paperId
        ORDER BY pa.position
        """,
    )
    fun observeAuthorNamesFor(paperId: String): Flow<List<String>>

    @Query("SELECT category_code FROM paper_categories WHERE paper_id = :paperId ORDER BY is_primary DESC")
    suspend fun categoryCodesFor(paperId: String): List<String>

    @Transaction
    suspend fun paperWithRelations(id: String): PaperWithRelations? {
        val paper = paperById(id) ?: return null
        return PaperWithRelations(
            paper = paper,
            authors = authorNamesFor(id),
            categories = categoryCodesFor(id),
        )
    }

    @Query("SELECT COUNT(*) FROM papers")
    suspend fun count(): Int
}
