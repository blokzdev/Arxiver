package dev.blokz.arxiver.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v15 → v16 (P-Ambient PA.1b): the ambient-digest exactly-once cursor. Additive only.
 *
 * `inbox_items.digested_at INTEGER` (nullable, no backfill) — the timestamp a row was counted into a digest
 * notification, else NULL. A row is eligible for a digest only while `digested_at IS NULL`; the worker stamps
 * exactly the counted rows before posting, so re-runs never double-count and a partial scoring pass can't drop
 * a paper from a later digest. Nullable-with-no-default is exactly the pre-P-Ambient meaning (never digested).
 */
val MIGRATION_15_16 =
    object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `inbox_items` ADD COLUMN `digested_at` INTEGER")
        }
    }
