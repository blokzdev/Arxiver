package dev.blokz.arxiver.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SPEC-DATA §4 (BLOB layout): one L2-normalized float32 vector per paper.
 * sqlite-vec remains a v2 optimization; the chunked scan over this table is
 * comfortably fast at orbit scale.
 */
@Entity(
    tableName = "paper_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = PaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paper_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PaperEmbeddingEntity(
    @PrimaryKey @ColumnInfo(name = "paper_id") val paperId: String,
    @ColumnInfo(name = "vector", typeAffinity = ColumnInfo.BLOB) val vector: ByteArray,
    @ColumnInfo(name = "model") val model: String,
    @ColumnInfo(name = "dim") val dim: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is PaperEmbeddingEntity && other.paperId == paperId && other.vector.contentEquals(vector)

    override fun hashCode(): Int = paperId.hashCode()

    companion object {
        fun floatsToBlob(vector: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            vector.forEach(buffer::putFloat)
            return buffer.array()
        }

        fun blobToFloats(blob: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(blob.size / 4) { buffer.getFloat(it * 4) }
        }
    }
}

/** SPEC-DATA §2 `related_papers` — precomputed top-K neighbors per library paper. */
@Entity(
    tableName = "related_papers",
    primaryKeys = ["paper_id", "neighbor_id"],
    foreignKeys = [
        ForeignKey(
            entity = PaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paper_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RelatedPaperEntity(
    @ColumnInfo(name = "paper_id") val paperId: String,
    @ColumnInfo(name = "neighbor_id") val neighborId: String,
    @ColumnInfo(name = "similarity") val similarity: Double,
    @ColumnInfo(name = "computed_at") val computedAt: Long,
)
