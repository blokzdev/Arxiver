package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.database.dao.ReadingPositionDao
import dev.blokz.arxiver.core.database.entity.ReadingPositionEntity
import dev.blokz.arxiver.core.database.toListDomain
import dev.blokz.arxiver.core.model.Paper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** A paper the user is mid-read on, for the "Continue reading" shelf (P-Read). Surface is `html`/`pdf`. */
data class ContinueReadingUi(
    val paper: Paper,
    val surface: String,
    val fraction: Float,
    val updatedAt: Long,
)

/**
 * The reading-continuity store (P-Read): durable per-(paper, surface) positions powering cross-session resume
 * (the reader VMs read/write via [upsert]/[get]) + the honest "Continue reading" shelf ([observeContinueReading]).
 * Thin over [ReadingPositionDao]. Positions are personal on-device data — this repo never exports/dispatches
 * them (guarded by ReadingPositionExportExclusionTest).
 */
class ReadingProgressRepository(
    private val dao: ReadingPositionDao,
    private val clock: () -> Long = { Instant.now().toEpochMilli() },
) {
    suspend fun upsert(position: ReadingPositionEntity) = dao.upsert(position)

    suspend fun get(
        paperId: String,
        surface: String,
    ): ReadingPositionEntity? = dao.get(paperId, surface)

    /** Furthest-progress, unfinished, not library-read, within the recency window — the shelf's honest gate. */
    fun observeContinueReading(): Flow<List<ContinueReadingUi>> =
        dao.observeContinueReading(PROGRESS_FLOOR, clock() - RECENCY_WINDOW_MS, SHELF_LIMIT)
            .map { rows ->
                rows.map { ContinueReadingUi(it.paper.toListDomain(), it.surface, it.fraction, it.updatedAt) }
            }

    companion object {
        /** Below this a paper was "opened, not read" — blocks the saved≠reading lie. Provisional (VERIFICATION §M). */
        const val PROGRESS_FLOOR = 0.02f

        /** A glance older than this stops nagging on the shelf. Provisional. */
        const val RECENCY_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

        /** Shelf cap — a calm resume list, not a backlog. Provisional. */
        const val SHELF_LIMIT = 5
    }
}
