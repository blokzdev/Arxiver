package dev.blokz.arxiver.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v8 → v9 (P4): add the durable `paper_feedback` table for the two-sided inbox ranker — additive,
 * zero rows touched. The `CREATE TABLE` + index SQL MUST byte-match what Room generates for
 * `9.json` (the `Migration8To9Test` identity-hash gate enforces this); mirror the exact column
 * order, quoting, and FK/`ON DELETE CASCADE` clause of [PaperFeedbackEntity].
 */
val MIGRATION_8_9 =
    object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `paper_feedback` (" +
                    "`paper_id` TEXT NOT NULL, `signal` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, PRIMARY KEY(`paper_id`), " +
                    "FOREIGN KEY(`paper_id`) REFERENCES `papers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_paper_feedback_signal` ON `paper_feedback` (`signal`)",
            )
        }
    }
