package dev.blokz.arxiver.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v12 → v13 (P-Explorer PE.1b): add `papers.landing_url` — additive, column-only, nullable, no backfill.
 *
 * Why it exists: an OSF-hosted paper (PsyArXiv) publishes **neither a DOI nor a PDF url**, so before this column
 * `Paper.canonicalUrl()` resolved to the empty string — the paper had no reachable link at all. `landing_url`
 * (`https://osf.io/szf8y`) is that paper's only way out to the web, and it slots between the DOI resolver and the
 * PDF url in `canonicalUrl()`.
 *
 * Existing rows stay NULL and keep their previous behavior exactly (DOI resolver, else PDF url) — nothing to
 * back-fill, since the value is only obtainable from a fresh OpenAlex response. Identity-hash gated by
 * `Migration12To13Test`. `ADD COLUMN` appends, matching `landingUrl` declared last on `PaperEntity`.
 */
val MIGRATION_12_13 =
    object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `papers` ADD COLUMN `landing_url` TEXT")
        }
    }
