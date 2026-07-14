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
     * Atomic reuse-or-insert (P-Explorer PE.3) — the single chokepoint for persisting a paper that may already
     * exist under another identity: same storage id, or another origin's row sharing the normalized DOI. One
     * `@Transaction` so a concurrent FollowSync upsert can neither fork a `doi_norm` nor be clobbered by a
     * thinner row arriving second. Returns the WINNING id — callers must use it, because reuse can re-key the
     * paper onto an arXiv/other-origin row.
     */
    @Transaction
    suspend fun insertPaperIfAbsentWithRelations(
        paper: PaperEntity,
        authors: List<String>,
        categories: List<String>,
    ): String {
        paperById(paper.id)?.let { return it.id }
        paper.doiNorm?.let { norm -> paperIdByDoi(norm)?.let { return it } }
        upsertPaperWithRelations(paper, authors, categories)
        return paper.id
    }

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
     * The canonical stored id for a DOI (cross-source de-dup) — arXiv-origin preferred (a paper that was also on
     * arXiv keys under the bare arXiv id), case-insensitive. Lets a follow *or an import* re-key onto an
     * already-stored row that shares the DOI instead of forking a second row.
     *
     * Matches the **normalized** key (P-Explorer PE.2): every caller passes `normalizeDoi(...)`, and every stored
     * row's `doi_norm` is written through that same function in `Paper.toEntity()` — so both sides agree by
     * construction. Before PE.2 this matched the RAW `doi` column, so a versioned DOI (`…7234721.v5`) never
     * de-duped.
     */
    @Query(
        "SELECT id FROM papers WHERE doi_norm = :doi COLLATE NOCASE ORDER BY (origin = 'arxiv') DESC, id ASC LIMIT 1",
    )
    suspend fun paperIdByDoi(doi: String): String?

    /** Every stored paper's storageId (P-Reader2 PFT.5.7 one-time PDF id-backfill: recover a cached PDF's
     *  storageId by matching its filename's sanitized prefix against these — the sound forward direction, since
     *  filename→id reverse-parse is non-injective). */
    @Query("SELECT id FROM papers")
    suspend fun allStorageIds(): List<String>

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
