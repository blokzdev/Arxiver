package dev.blokz.arxiver.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** SPEC-DATA §2 `library_entries`. */
@Entity(
    tableName = "library_entries",
    foreignKeys = [
        ForeignKey(
            entity = PaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paper_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LibraryEntryEntity(
    @PrimaryKey @ColumnInfo(name = "paper_id") val paperId: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "status") val status: String = STATUS_TO_READ,
    @ColumnInfo(name = "rating") val rating: Int? = null,
    @ColumnInfo(name = "pdf_path") val pdfPath: String? = null,
) {
    companion object {
        const val STATUS_TO_READ = "to_read"
        const val STATUS_READING = "reading"
        const val STATUS_READ = "read"
    }
}

@Entity(
    tableName = "collections",
    indices = [Index(value = ["name"], unique = true)],
)
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "collection_papers",
    primaryKeys = ["collection_id", "paper_id"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collection_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paper_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("paper_id")],
)
data class CollectionPaperCrossRef(
    @ColumnInfo(name = "collection_id") val collectionId: Long,
    @ColumnInfo(name = "paper_id") val paperId: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name", collate = ColumnInfo.NOCASE) val name: String,
)

@Entity(
    tableName = "paper_tags",
    primaryKeys = ["paper_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = PaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paper_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tag_id")],
)
data class PaperTagCrossRef(
    @ColumnInfo(name = "paper_id") val paperId: String,
    @ColumnInfo(name = "tag_id") val tagId: Long,
)

@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = PaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paper_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("paper_id")],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "paper_id") val paperId: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** SPEC-DATA §2 `inbox_items` — triage feed rows from follow syncs. */
@Entity(
    tableName = "inbox_items",
    foreignKeys = [
        ForeignKey(
            entity = PaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paper_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("state"), Index("arrived_at")],
)
data class InboxItemEntity(
    @PrimaryKey @ColumnInfo(name = "paper_id") val paperId: String,
    @ColumnInfo(name = "follow_id") val followId: Long,
    @ColumnInfo(name = "arrived_at") val arrivedAt: Long,
    @ColumnInfo(name = "state") val state: String = STATE_NEW,
    @ColumnInfo(name = "score") val score: Double? = null,
    /**
     * When this row was counted into an ambient digest (P-Ambient PA.1b), else null. The exactly-once cursor:
     * a row is eligible for a digest only while `digested_at IS NULL`, and is stamped on exactly the counted
     * rows before the notification posts — per-row and order-independent, so a stopped/partial scoring pass
     * can't lose a paper from a later digest (a DataStore watermark would). Never exported (local triage state).
     */
    @ColumnInfo(name = "digested_at") val digestedAt: Long? = null,
) {
    companion object {
        const val STATE_NEW = "new"
        const val STATE_SEEN = "seen"
        const val STATE_SAVED = "saved"
        const val STATE_DISMISSED = "dismissed"
    }
}
