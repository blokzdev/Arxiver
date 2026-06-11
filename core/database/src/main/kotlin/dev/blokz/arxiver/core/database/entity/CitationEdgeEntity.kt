package dev.blokz.arxiver.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * SPEC-DATA §2 `citation_edges`: citing → cited, arXiv-id endpoints. Unknown
 * endpoints get stub rows in `papers` (source = s2_stub) so joins always hold.
 */
@Entity(
    tableName = "citation_edges",
    primaryKeys = ["citing_id", "cited_id"],
    indices = [Index("cited_id")],
)
data class CitationEdgeEntity(
    @ColumnInfo(name = "citing_id") val citingId: String,
    @ColumnInfo(name = "cited_id") val citedId: String,
    @ColumnInfo(name = "source") val source: String = "s2",
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)
