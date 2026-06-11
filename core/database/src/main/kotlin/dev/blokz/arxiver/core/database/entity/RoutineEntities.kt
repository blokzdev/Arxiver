package dev.blokz.arxiver.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * SPEC-DATA §2 `routine_configs`. `token_alias` keys into the Keystore-backed
 * vault — the token itself NEVER touches this table (CLAUDE.md red line).
 */
@Entity(tableName = "routine_configs")
data class RoutineConfigEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "trigger_url") val triggerUrl: String,
    @ColumnInfo(name = "token_alias") val tokenAlias: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long? = null,
    @ColumnInfo(name = "auth_invalid") val authInvalid: Boolean = false,
)

@Entity(
    tableName = "routine_dispatches",
    foreignKeys = [
        ForeignKey(
            entity = RoutineConfigEntity::class,
            parentColumns = ["id"],
            childColumns = ["routine_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("routine_id"), Index("status"), Index("created_at")],
)
data class RoutineDispatchEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "routine_id") val routineId: Long,
    @ColumnInfo(name = "action") val action: String,
    @ColumnInfo(name = "paper_count") val paperCount: Int,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "status") val status: String = STATUS_QUEUED,
    @ColumnInfo(name = "http_code") val httpCode: Int? = null,
    @ColumnInfo(name = "error") val error: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "sent_at") val sentAt: Long? = null,
) {
    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_SENT = "sent"
        const val STATUS_FAILED = "failed"
    }
}
