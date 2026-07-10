package dev.blokz.arxiver.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.blokz.arxiver.core.model.normalizeDoi

/**
 * v11 → v12 (P-Explorer PE.2): add `papers.doi_norm` — the cross-source de-dup KEY — and re-point the index at it.
 *
 * **The bug this fixes.** `paperIdByDoi` matched the RAW `doi` column, but its only callers pass
 * `normalizeDoi(...)`. A versioned chemRxiv DOI (`10.26434/chemrxiv.7234721.v5`) is stored raw and queried
 * stripped, so it silently failed to de-dup — the exact fork PFP.1 was built to prevent. `doi` stays the verbatim
 * citeable value for display/export; `doi_norm` is purely the match key, written through `Paper.toEntity()`.
 *
 * **Backfill runs in Kotlin, not SQL, on purpose.** SQLite has no `REGEXP`, so a pure-SQL backfill could lowercase
 * but never strip the `.vN` suffix — i.e. it would leave exactly the rows this migration exists to fix still
 * broken. Reusing [normalizeDoi] guarantees the back-filled key is byte-identical to what future writes produce.
 * The `papers` table is single-user-sized (hundreds–thousands of rows), so a one-time row walk is cheap. Rows are
 * collected before updating — never mutate a table while walking a cursor over it.
 *
 * Additive: `ADD COLUMN` appends (matching `doiNorm` declared last on `PaperEntity`), the new index is NON-unique
 * (two rows may legitimately share a DOI), and the now-dead `index_papers_doi` is dropped — `paperIdByDoi` was its
 * sole consumer. Identity-hash gated by `Migration11To12Test`.
 */
val MIGRATION_11_12 =
    object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `papers` ADD COLUMN `doi_norm` TEXT")

            // Collect first, then update — mutating `papers` while its cursor is open is undefined behavior.
            val pending = mutableListOf<Pair<String, String>>()
            db.query("SELECT `id`, `doi` FROM `papers` WHERE `doi` IS NOT NULL").use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val norm = normalizeDoi(cursor.getString(1)) ?: continue
                    pending += id to norm
                }
            }
            pending.forEach { (id, norm) ->
                db.execSQL("UPDATE `papers` SET `doi_norm` = ? WHERE `id` = ?", arrayOf<Any>(norm, id))
            }

            db.execSQL("DROP INDEX IF EXISTS `index_papers_doi`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_papers_doi_norm` ON `papers` (`doi_norm`)")
        }
    }
