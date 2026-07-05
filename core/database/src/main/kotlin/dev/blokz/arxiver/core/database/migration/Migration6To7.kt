package dev.blokz.arxiver.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v6 → v7 (P-Sources PS.0): source-identity columns on `papers` — additive only.
 *
 * `origin` is a NEW identity-origin discriminator (arxiv/chemrxiv/…). It is deliberately **NOT** an
 * overload of `papers.source` (the `PaperSource` acquisition enum that `EmbeddingDao` filters
 * `!= 'S2_STUB'`) — reusing `source` would corrupt embedding-eligibility (the `MIGRATION_5_6`
 * anti-repurpose lesson). `native_id` holds a source native id (DOI) for non-arXiv rows; NULL for arXiv
 * (its bare id IS the native id). **Every existing row backfills `origin='arxiv'` in place — bare arXiv
 * ids are NOT re-keyed.** `TEXT NOT NULL DEFAULT 'arxiv'` MUST byte-match `@ColumnInfo(defaultValue =
 * "'arxiv'")` (identity-hash gate). No DROP / rebuild.
 */
val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `papers` ADD COLUMN `origin` TEXT NOT NULL DEFAULT 'arxiv'")
            db.execSQL("ALTER TABLE `papers` ADD COLUMN `native_id` TEXT")
        }
    }
