package dev.blokz.arxiver.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v14 → v15 (P5.5): calibration downgrade hysteresis. Additive only.
 *
 * `relevance_model.consecutive_null_fits INTEGER NOT NULL DEFAULT 0` — the persisted streak that lets the
 * runner keep the previous calibration for exactly one failed refit before downgrading to the legacy 0.55
 * (two-pass-confirm on the downgrade direction only; a fresh fit always applies immediately). Persisted —
 * not in-memory — because Android routinely kills the process between periodic worker passes, and an
 * in-memory streak that never reaches 2 would retain a stale calibration indefinitely. DEFAULT 0 backfills
 * every existing row as "current fit is fresh", which is exactly the pre-P5.5 semantics.
 */
val MIGRATION_14_15 =
    object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `relevance_model` ADD COLUMN `consecutive_null_fits` INTEGER NOT NULL DEFAULT 0",
            )
        }
    }
