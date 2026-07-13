package dev.blokz.arxiver.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Upsert
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.database.entity.ReadingPositionEntity
import kotlinx.coroutines.flow.Flow

/** One "Continue reading" shelf card: the paper + the represented (furthest-progress) row's surface/progress. */
data class ContinueReadingRow(
    @Embedded val paper: PaperEntity,
    val surface: String,
    val fraction: Float,
    val updatedAt: Long,
)

@Dao
interface ReadingPositionDao {
    /**
     * UPDATE-in-place upsert (never `@Insert(REPLACE)`), so a future annotations table child-FK'ing this row
     * is never REPLACE-churned out from under it.
     */
    @Upsert
    suspend fun upsert(position: ReadingPositionEntity)

    @Query("SELECT * FROM reading_positions WHERE paper_id = :paperId AND surface = :surface")
    suspend fun get(
        paperId: String,
        surface: String,
    ): ReadingPositionEntity?

    /** Not wired yet — there is no user-facing paper-delete path today; provided for when one is added (P-Read). */
    @Query("DELETE FROM reading_positions WHERE paper_id = :paperId")
    suspend fun deleteByPaper(paperId: String)

    /**
     * The honest "Continue reading" shelf (P-Read): each paper represented by its **furthest-progress** row
     * (so an 80%-HTML read is never buried under a 3%-PDF glance), excluding papers you have finished or
     * marked read, above a progress floor, within a recency window, papers-only.
     *
     * - **No window functions** (minSdk 26 ships SQLite 3.19; `ROW_NUMBER() OVER` needs 3.25 / API 30): the
     *   inner `GROUP BY paper_id` + `MAX(fraction)` uses SQLite's bare-column rule — `surface`/`updated_at`
     *   take their values from the max-fraction row.
     * - **Paper-level** finished/read exclusion (not row-level): a paper finished in HTML is not resurfaced
     *   by a later 3% glance in the PDF viewer — `NOT EXISTS(finished=1)` + `status != 'read'`.
     * - INNER JOIN `papers` self-filters an orphan row (the no-FK, paperless-reader-open case).
     */
    @Query(
        """
        SELECT p.*, rp.surface AS surface, rp.fraction AS fraction, rp.updated_at AS updatedAt
        FROM (
            SELECT paper_id, surface, MAX(fraction) AS fraction, updated_at
            FROM reading_positions
            GROUP BY paper_id
        ) rp
        JOIN papers p ON p.id = rp.paper_id
        LEFT JOIN library_entries le ON le.paper_id = rp.paper_id
        WHERE rp.fraction >= :floor
          AND rp.updated_at >= :recencyFloor
          AND (le.status IS NULL OR le.status != 'read')
          AND NOT EXISTS (
              SELECT 1 FROM reading_positions f WHERE f.paper_id = rp.paper_id AND f.finished = 1
          )
        ORDER BY rp.updated_at DESC
        LIMIT :limit
        """,
    )
    fun observeContinueReading(
        floor: Float,
        recencyFloor: Long,
        limit: Int,
    ): Flow<List<ContinueReadingRow>>
}
