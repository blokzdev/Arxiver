package dev.blokz.arxiver.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * SPEC-DATA §2 `follows`. `value` is a category code, author name, or raw query. `origin` (P-Feeds PF.2) is the
 * source whose feed a follow subscribes to — `arxiv` (default, native Atom), `biorxiv`/`medrxiv` (native
 * api.biorxiv.org), `chemrxiv`/… (OpenAlex). The unique index is (type, value, origin) so the same category can
 * be followed on multiple sources (e.g. `neuroscience` on bioRxiv AND some day arXiv) without colliding.
 */
@Entity(
    tableName = "follows",
    indices = [Index(value = ["type", "value", "origin"], unique = true)],
)
data class FollowEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "value") val value: String,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long? = null,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "origin", defaultValue = "'arxiv'") val origin: String = "arxiv",
) {
    companion object {
        const val TYPE_CATEGORY = "category"
        const val TYPE_AUTHOR = "author"
        const val TYPE_QUERY = "query"
    }
}
