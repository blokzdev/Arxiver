package dev.blokz.arxiver.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Mirrors SPEC-DATA §2 `papers`. Timestamps are epoch millis. */
@Entity(
    tableName = "papers",
    indices = [
        Index("primary_category"),
        Index("updated_at"),
        // Cross-source de-dup lookup by DOI (P-FeedPolish). NON-unique — existing installs may already hold two
        // rows sharing a DOI (an imported arXiv row with a `doi` + a followed `chemrxiv:` row).
        Index("doi"),
    ],
)
data class PaperEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "latest_version") val latestVersion: Int,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "abstract") val abstract: String,
    @ColumnInfo(name = "published_at") val publishedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "primary_category") val primaryCategory: String,
    /** Denormalized "A, B, C" author display line — list rows avoid join fan-out. */
    @ColumnInfo(name = "authors_line", defaultValue = "") val authorsLine: String,
    @ColumnInfo(name = "comment") val comment: String?,
    @ColumnInfo(name = "journal_ref") val journalRef: String?,
    @ColumnInfo(name = "doi") val doi: String?,
    @ColumnInfo(name = "pdf_url") val pdfUrl: String,
    @ColumnInfo(name = "citation_count") val citationCount: Int?,
    @ColumnInfo(name = "s2_paper_id") val s2PaperId: String?,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @ColumnInfo(name = "embedded_at") val embeddedAt: Long?,
    @ColumnInfo(name = "citations_synced_at") val citationsSyncedAt: Long?,
    // P-Sources PS.0: source-identity discriminators (appended last — Room's identity hash is
    // column-order-sensitive and ADD COLUMN appends). `origin` is a NEW column, NOT an overload of the
    // `source` acquisition enum. The SQL `DEFAULT 'arxiv'` MUST byte-match `defaultValue = "'arxiv'"`.
    @ColumnInfo(name = "origin", defaultValue = "'arxiv'") val origin: String = "arxiv",
    @ColumnInfo(name = "native_id") val nativeId: String? = null,
)
