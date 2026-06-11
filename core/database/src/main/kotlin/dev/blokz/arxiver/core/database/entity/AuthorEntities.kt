package dev.blokz.arxiver.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "authors",
    indices = [Index(value = ["name"], unique = true)],
)
data class AuthorEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "s2_author_id") val s2AuthorId: String? = null,
)

@Entity(
    tableName = "paper_authors",
    primaryKeys = ["paper_id", "author_id"],
    foreignKeys = [
        ForeignKey(
            entity = PaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paper_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AuthorEntity::class,
            parentColumns = ["id"],
            childColumns = ["author_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("author_id")],
)
data class PaperAuthorCrossRef(
    @ColumnInfo(name = "paper_id") val paperId: String,
    @ColumnInfo(name = "author_id") val authorId: Long,
    @ColumnInfo(name = "position") val position: Int,
)
