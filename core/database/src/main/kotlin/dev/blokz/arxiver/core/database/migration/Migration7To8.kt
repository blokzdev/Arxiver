package dev.blokz.arxiver.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v7 → v8 (P-Feeds PF.2): generalize `follows` to track the source of each subscription — additive.
 *
 * `origin` (`arxiv`/`biorxiv`/`medrxiv`/`chemrxiv`/…) is a NEW column; every existing row backfills
 * `origin='arxiv'` in place (bare arXiv follows are NOT re-keyed). `TEXT NOT NULL DEFAULT 'arxiv'` MUST
 * byte-match `@ColumnInfo(defaultValue = "'arxiv'")` on [FollowEntity] (identity-hash gate) — this is the
 * exact `MIGRATION_6_7` pattern for `papers.origin`.
 *
 * The unique index widens from `(type, value)` to `(type, value, origin)` so the same category can be
 * followed on more than one source. SQLite has no `ALTER INDEX`, so DROP the old index + CREATE the new one;
 * the index names must byte-match what Room generates for `8.json`.
 */
val MIGRATION_7_8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `follows` ADD COLUMN `origin` TEXT NOT NULL DEFAULT 'arxiv'")
            db.execSQL("DROP INDEX IF EXISTS `index_follows_type_value`")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_follows_type_value_origin` " +
                    "ON `follows` (`type`, `value`, `origin`)",
            )
        }
    }
