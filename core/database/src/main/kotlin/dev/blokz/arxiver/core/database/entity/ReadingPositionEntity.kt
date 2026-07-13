package dev.blokz.arxiver.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * A durable reading position for the "Continue reading" shelf + cross-session resume (Phase P-Read). One row
 * per **(paper, surface)** — the HTML reader (anchor + CSS-px) and the PDF viewer (page + intra-page offset)
 * are different coordinate systems, so they never share a row. Anchor-capable: [anchorId] / [offsetPx] /
 * [fraction] mirror the `:core:ai` `ReaderPosition` loss-free, so a future annotations phase reuses this
 * substrate (its own sibling ranges table child-FKs this row — hence the DAO upserts UPDATE-in-place, never
 * REPLACE-churns).
 *
 * A stored row means "opened + last-known position", **NOT proof of progress** — every consumer re-applies
 * the honesty floors (fraction ≥ floor, finished = 0, library status ≠ 'read', recency). **No FK to `papers`**:
 * the HTML reader tolerates a paperless open (`HtmlReaderViewModel.load()` version `?: 1`), so an FK-cascade
 * insert would *fail*; the shelf query INNER JOINs `papers`, so an orphan row self-filters. This table is
 * personal on-device behavioural data — never exported, dispatched, or backed up (structurally enforced).
 */
@Entity(
    tableName = "reading_positions",
    primaryKeys = ["paper_id", "surface"],
    indices = [Index("updated_at")],
)
data class ReadingPositionEntity(
    @ColumnInfo(name = "paper_id") val paperId: String,
    /** [SURFACE_HTML] or [SURFACE_PDF]. */
    @ColumnInfo(name = "surface") val surface: String,
    /** The SERVED version — HTML `servedVersion` (which `newest()` can make < latestVersion); PDF latestVersion. */
    @ColumnInfo(name = "version") val version: Int,
    /** HTML nearest-anchor id; null for PDF. */
    @ColumnInfo(name = "anchor_id") val anchorId: String?,
    /** HTML `offsetCssPx` / PDF intra-page `firstVisibleItemScrollOffset`. */
    @ColumnInfo(name = "offset_px") val offsetPx: Int,
    /** Universal progress in [0,1] — drives the shelf position marker + the honesty floor. */
    @ColumnInfo(name = "fraction") val fraction: Float,
    /** PDF `firstVisibleItemIndex`; null for HTML. */
    @ColumnInfo(name = "page_index") val pageIndex: Int?,
    /** HTML-only sustained-dwell flag; EXCLUSION-ONLY (drops the paper, never renders a "completed" badge). */
    @ColumnInfo(name = "finished", defaultValue = "0") val finished: Boolean = false,
    /** Local scroll-probe timestamp — the recency signal; never leaves the device. */
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val SURFACE_HTML = "html"
        const val SURFACE_PDF = "pdf"
    }
}
