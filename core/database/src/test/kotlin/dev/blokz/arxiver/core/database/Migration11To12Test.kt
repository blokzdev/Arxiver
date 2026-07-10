package dev.blokz.arxiver.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.blokz.arxiver.core.database.migration.MIGRATION_11_12
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * v11 → v12 (P-Explorer PE.2). `runMigrationsAndValidate` recreates v11 from `11.json`, applies
 * [MIGRATION_11_12], and asserts the result matches the committed `12.json` (identity hash) — pinning the added
 * column, the dropped `index_papers_doi`, and the new `index_papers_doi_norm`.
 *
 * The behavioural half matters more than the schema half: the whole point of `doi_norm` is that a **versioned**
 * chemRxiv DOI, stored raw, must back-fill to its *stripped* form — the case a pure-SQL backfill could never
 * handle (SQLite has no `REGEXP`) and the exact case that silently failed to de-dup before this migration.
 */
@RunWith(RobolectricTestRunner::class)
class Migration11To12Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ArxiverDatabase::class.java,
        )

    private fun insertPaper(
        id: String,
        doi: String?,
    ): String {
        val doiSql = doi?.let { "'$it'" } ?: "NULL"
        return "INSERT INTO papers (id, latest_version, title, abstract, published_at, updated_at, " +
            "primary_category, authors_line, pdf_url, source, fetched_at, doi) " +
            "VALUES ('$id', 1, 'T', 'A', 0, 0, 'cs.LG', '', '', 'arxiv', 0, $doiSql)"
    }

    private fun doiNormOf(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
    ): String? =
        db.query("SELECT doi_norm FROM papers WHERE id = '$id'").use { c ->
            c.moveToFirst()
            if (c.isNull(0)) null else c.getString(0)
        }

    @Test
    fun migrate11To12_addsDoiNorm_backfillsNormalized_andValidatesSchema() {
        helper.createDatabase(TEST_DB, 11).apply {
            // A versioned chemRxiv DOI stored RAW — the row that could never de-dup before PE.2.
            execSQL(insertPaper("chemrxiv:10.26434/chemrxiv.7234721.v5", "10.26434/chemrxiv.7234721.v5"))
            // Mixed case + a `https://doi.org/` prefix — both must normalize away.
            execSQL(insertPaper("chemrxiv:10.26434/ChemRxiv-2024-ABC", "https://doi.org/10.26434/ChemRxiv-2024-ABC"))
            // A paper with no DOI at all must stay NULL (not "" — a blank key would collide across rows).
            execSQL(insertPaper("2403.09999", null))
            close()
        }

        // Identity-hash gate: throws unless MIGRATION_11_12 reproduces 12.json exactly (column + both indices).
        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, MIGRATION_11_12)

        assertEquals(
            "10.26434/chemrxiv.7234721",
            doiNormOf(db, "chemrxiv:10.26434/chemrxiv.7234721.v5"),
            "the `.vN` suffix must be stripped — a pure-SQL backfill could not do this",
        )
        assertEquals(
            "10.26434/chemrxiv-2024-abc",
            doiNormOf(db, "chemrxiv:10.26434/ChemRxiv-2024-ABC"),
            "prefix stripped and lowercased",
        )
        assertNull(doiNormOf(db, "2403.09999"), "a DOI-less paper keeps a NULL key, never a blank one")

        // The verbatim citeable DOI is untouched — `doi_norm` is a match key, not a replacement.
        db.query("SELECT doi FROM papers WHERE id = 'chemrxiv:10.26434/chemrxiv.7234721.v5'").use { c ->
            c.moveToFirst()
            assertEquals("10.26434/chemrxiv.7234721.v5", c.getString(0))
        }
        db.close()
    }

    @Test
    fun migrate11To12_twoRowsMayShareANormalizedDoi_theIndexIsNotUnique() {
        helper.createDatabase(TEST_DB2, 11).apply {
            // An imported arXiv row and a followed chemRxiv row can legitimately share a DOI — a UNIQUE index
            // here would throw mid-migration and brick the upgrade (the PFP.1 anti-brick lesson, re-pinned).
            execSQL(insertPaper("2403.09999", "10.1101/shared.v1"))
            execSQL(insertPaper("chemrxiv:10.1101/shared", "10.1101/shared"))
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB2, 12, true, MIGRATION_11_12)

        db.query("SELECT COUNT(*) FROM papers WHERE doi_norm = '10.1101/shared'").use { c ->
            c.moveToFirst()
            assertEquals(2, c.getInt(0), "both rows normalize to the same key and coexist — index is NON-unique")
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-11-12-test"
        private const val TEST_DB2 = "migration-11-12-nonunique-test"
    }
}
