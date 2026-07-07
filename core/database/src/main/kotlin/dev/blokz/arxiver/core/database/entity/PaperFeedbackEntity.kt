package dev.blokz.arxiver.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * SPEC-DATA §2 `paper_feedback` — durable per-paper relevance labels for the two-sided inbox
 * ranker (P4). Deliberately decoupled from `inbox_items` so a dismissed paper's negative signal
 * survives [dev.blokz.arxiver.core.database.dao.InboxDao.pruneDismissed] (which evicts inbox rows
 * on an `arrived_at` deadline, before the ranker ever reads them).
 *
 * One row per paper (`paper_id` PK): a later thumb upserts over an earlier dismiss. The FK CASCADE
 * is correct — a label is useless without the paper's embedding, which also CASCADE-dies with the
 * paper. **Red line:** this table is local-only — never exported, backed up, or logged.
 */
@Entity(
    tableName = "paper_feedback",
    foreignKeys = [
        ForeignKey(
            entity = PaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paper_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("signal")],
)
data class PaperFeedbackEntity(
    @PrimaryKey @ColumnInfo(name = "paper_id") val paperId: String,
    @ColumnInfo(name = "signal") val signal: Int,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    companion object {
        const val SIGNAL_POSITIVE = 1
        const val SIGNAL_NEGATIVE = -1

        /** Revealed: the user swiped the paper away in the inbox. */
        const val SOURCE_DISMISS = "dismiss"

        /** Explicit: the user tapped a relevance thumb (P4.2). */
        const val SOURCE_THUMB = "thumb"
    }
}
