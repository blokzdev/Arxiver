package dev.blokz.arxiver.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.blokz.arxiver.core.database.migration.MIGRATION_6_7
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v6 → v7 migration (P-Sources PS.0). `runMigrationsAndValidate` recreates v6 from `6.json`, applies
 * [MIGRATION_6_7], and asserts the result matches the committed `7.json` (identity hash) — so the
 * hand-written `ALTER` can't drift from the generated schema. Also the **zero-rows-re-keyed guarantee**:
 * a pre-existing arXiv row's opaque `id` PK is byte-unchanged and `origin` backfills to `'arxiv'`
 * (identity is additive, never a re-key — the load-bearing P-Sources premise).
 */
@RunWith(RobolectricTestRunner::class)
class Migration6To7Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ArxiverDatabase::class.java,
        )

    @Test
    fun migrate6To7_addsOriginNativeId_arxivRowByteUnchanged_andValidatesSchema() {
        val bareId = "2403.09999"
        helper.createDatabase(TEST_DB, 6).apply {
            // Full v6 `papers` column set (cannot reference origin/native_id pre-migration).
            execSQL(
                "INSERT INTO papers " +
                    "(id, latest_version, title, abstract, published_at, updated_at, primary_category, " +
                    "authors_line, comment, journal_ref, doi, pdf_url, citation_count, s2_paper_id, source, " +
                    "fetched_at, embedded_at, citations_synced_at) VALUES " +
                    "('$bareId', 1, 'T', 'a', 0, 0, 'cs.LG', '', NULL, NULL, NULL, " +
                    "'https://arxiv.org/pdf/$bareId', NULL, NULL, 'SEARCH', 0, NULL, NULL)",
            )
            close()
        }

        // Identity-hash gate: throws if MIGRATION_6_7's SQL doesn't reproduce 7.json exactly.
        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        db.query("SELECT id, origin, native_id FROM papers").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(bareId, c.getString(0), "existing arXiv row id is byte-unchanged (NOT re-keyed)")
            assertEquals("arxiv", c.getString(1), "origin backfills to 'arxiv' in place")
            assertTrue(c.isNull(2), "native_id is NULL for a migrated arXiv row")
            assertEquals(1, c.count, "exactly one row — none dropped or duplicated")
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-6-7-test"
    }
}
