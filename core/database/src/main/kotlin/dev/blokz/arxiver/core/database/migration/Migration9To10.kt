package dev.blokz.arxiver.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v9 → v10 (P-FeedPolish PFP.1): index `papers.doi` for the cross-source de-dup lookup — additive, index-only.
 *
 * **NON-unique** on purpose: existing installs may already hold two rows sharing a DOI (an imported arXiv row
 * with a `doi` set + a followed `chemrxiv:` row), so a `CREATE UNIQUE INDEX` would throw mid-migration and brick
 * the upgrade. The index name must byte-match what Room generates for `PaperEntity`'s `Index("doi")` (the
 * `Migration9To10Test` identity-hash gate enforces this). No column added ⇒ no `defaultValue` byte-match hazard.
 * Ingest-keying only — no existing row is re-keyed, so no CASCADE carrier is stranded.
 */
val MIGRATION_9_10 =
    object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_papers_doi` ON `papers` (`doi`)")
        }
    }
