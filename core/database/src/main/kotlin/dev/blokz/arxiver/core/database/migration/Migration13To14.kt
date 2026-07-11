package dev.blokz.arxiver.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v13 → v14 (P5.3): the per-user relevance-model store + the label-time exposure column. Additive only.
 *
 * - `relevance_model` — single row (invariant enforced in the DAO, not SQL), **never seeded**: absent ≡ below
 *   the calibration floor ≡ the legacy 0.55 constant, so fresh and migrated installs behave identically.
 * - `paper_feedback.score_at_label REAL` (nullable, no backfill — the exposure context is unrecoverable for
 *   old labels, which is exactly why it must be captured at label time from now on): the paper's inbox score
 *   at the moment the user dismissed/thumbed. Top-k changes label *generation* (the k+1-th paper renders in a
 *   different section with different dismiss economics), and future analyses need to condition on it.
 */
val MIGRATION_13_14 =
    object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `relevance_model` (" +
                    "`id` INTEGER NOT NULL, `embedding_model` TEXT NOT NULL, " +
                    "`calibration_a` REAL, `calibration_b` REAL, " +
                    "`shrinkage_lambda` REAL NOT NULL, " +
                    "`label_positives` INTEGER NOT NULL, `label_negatives` INTEGER NOT NULL, " +
                    "`fitted_at` INTEGER NOT NULL, " +
                    "`head_weights` BLOB, `head_bias` REAL, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL("ALTER TABLE `paper_feedback` ADD COLUMN `score_at_label` REAL")
        }
    }
