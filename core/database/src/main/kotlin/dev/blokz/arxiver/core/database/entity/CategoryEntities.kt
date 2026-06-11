package dev.blokz.arxiver.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey @ColumnInfo(name = "code") val code: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "group_name") val groupName: String,
)

/**
 * Note: no FK to `categories` — papers can carry category codes outside the
 * bundled taxonomy (arXiv occasionally adds categories before we ship updates).
 */
@Entity(
    tableName = "paper_categories",
    primaryKeys = ["paper_id", "category_code"],
    foreignKeys = [
        ForeignKey(
            entity = PaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paper_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("category_code")],
)
data class PaperCategoryCrossRef(
    @ColumnInfo(name = "paper_id") val paperId: String,
    @ColumnInfo(name = "category_code") val categoryCode: String,
    @ColumnInfo(name = "is_primary") val isPrimary: Boolean,
)
