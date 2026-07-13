package dev.blokz.arxiver.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v16 → v17 (Phase P-Read): the durable reading-position store powering cross-session resume + the honest
 * "Continue reading" shelf. Additive only — a new table; no existing table is touched.
 *
 * `reading_positions` — one row per (paper_id, surface); anchor-capable (anchor_id/offset_px/fraction) so a
 * future annotations phase reuses it. No FK to `papers` (the HTML reader tolerates a paperless open; the
 * shelf INNER JOINs papers so an orphan self-filters). Personal on-device data — never exported/backed up.
 *
 * The DDL is transcribed from the generated `17.json` with Room's templated table-name macro substituted to
 * the real `reading_positions` (a verbatim paste of the macro would create a mis-named table and fail the
 * identity-hash validation in Migration16To17Test).
 */
val MIGRATION_16_17 =
    object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `reading_positions` (" +
                    "`paper_id` TEXT NOT NULL, `surface` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
                    "`anchor_id` TEXT, `offset_px` INTEGER NOT NULL, `fraction` REAL NOT NULL, " +
                    "`page_index` INTEGER, `finished` INTEGER NOT NULL DEFAULT 0, " +
                    "`updated_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`paper_id`, `surface`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reading_positions_updated_at` " +
                    "ON `reading_positions` (`updated_at`)",
            )
        }
    }
