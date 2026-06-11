package dev.blokz.arxiver.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** SPEC-DATA §2 `follows`. `value` is a category code, author name, or raw query. */
@Entity(
    tableName = "follows",
    indices = [Index(value = ["type", "value"], unique = true)],
)
data class FollowEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "value") val value: String,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long? = null,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
) {
    companion object {
        const val TYPE_CATEGORY = "category"
        const val TYPE_AUTHOR = "author"
        const val TYPE_QUERY = "query"
    }
}
