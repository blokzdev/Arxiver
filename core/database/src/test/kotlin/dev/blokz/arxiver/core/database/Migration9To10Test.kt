package dev.blokz.arxiver.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.blokz.arxiver.core.database.migration.MIGRATION_9_10
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v9 → v10 migration (P-FeedPolish PFP.1). `runMigrationsAndValidate` recreates v9 from `9.json`, applies
 * [MIGRATION_9_10], and asserts the result matches the committed `10.json` (identity hash) — so the hand-written
 * `CREATE INDEX` byte-matches Room's `Index("doi")`. Also proves the index is **NON-unique**: two pre-existing
 * rows sharing a DOI survive the migration (a `CREATE UNIQUE INDEX` would throw here and brick the upgrade).
 */
@RunWith(RobolectricTestRunner::class)
class Migration9To10Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ArxiverDatabase::class.java,
        )

    private fun insertPaper(
        id: String,
        doi: String,
    ) = "INSERT INTO papers (id, latest_version, title, abstract, published_at, updated_at, primary_category, " +
        "pdf_url, source, fetched_at, doi) VALUES ('$id', 1, 'T', 'A', 0, 0, 'cs.LG', '', 'arxiv', 0, '$doi')"

    @Test
    fun migrate9To10_addsDoiIndex_nonUnique_andValidatesSchema() {
        helper.createDatabase(TEST_DB, 9).apply {
            // TWO v9 rows sharing a DOI (an imported arXiv row + a followed non-arXiv row) — the anti-brick case.
            execSQL(insertPaper("2403.09999", "10.1101/shared"))
            execSQL(insertPaper("chemrxiv:10.1101/shared", "10.1101/shared"))
            close()
        }

        // Identity-hash gate: throws if MIGRATION_9_10 doesn't reproduce 10.json exactly (index name must match).
        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_9_10)

        db.query("SELECT COUNT(*) FROM papers WHERE doi = '10.1101/shared'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0), "both same-DOI rows survive — the index is NON-unique (no brick)")
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-9-10-test"
    }
}
