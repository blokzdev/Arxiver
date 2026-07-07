package dev.blokz.arxiver.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v10 → v11 (P-FeedPolish PFP.3): add `follows.empty_sync_streak` for the follow health hint — additive,
 * column-only. Every existing row back-fills `0` (a fresh streak).
 *
 * Bare `INTEGER NOT NULL DEFAULT 0` (no quotes) MUST byte-match `@ColumnInfo(defaultValue = "0")` on
 * [dev.blokz.arxiver.core.database.entity.FollowEntity] — the identity-hash gate in `Migration10To11Test`
 * enforces it. This is the `MIGRATION_7_8` additive-column idiom (that one used a TEXT `DEFAULT 'arxiv'`; a
 * numeric default has no quote-normalization hazard). The unique index `(type, value, origin)` is untouched.
 */
val MIGRATION_10_11 =
    object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `follows` ADD COLUMN `empty_sync_streak` INTEGER NOT NULL DEFAULT 0")
        }
    }
