package dev.blokz.arxiver.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.blokz.arxiver.core.database.migration.MIGRATION_15_16
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v15 → v16 (P-Ambient PA.1b). Identity-hash gate + the sqlite_master DDL guard, and the semantic invariant:
 * an existing inbox row migrates with `digested_at = NULL` (never digested) — the exact pre-P-Ambient meaning,
 * so migrated and fresh installs behave identically.
 */
@RunWith(RobolectricTestRunner::class)
class Migration15To16Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ArxiverDatabase::class.java,
        )

    @Test
    fun migrate15To16_addsDigestedAt_backfillsNull_preservesTheRow() {
        helper.createDatabase(TEST_DB, 15).apply {
            execSQL(
                "INSERT INTO papers (id, latest_version, title, abstract, published_at, updated_at, " +
                    "primary_category, authors_line, pdf_url, source, fetched_at) " +
                    "VALUES ('2403.09999', 1, 'T', 'A', 0, 0, 'cs.LG', '', '', 'arxiv', 0)",
            )
            execSQL(
                "INSERT INTO inbox_items (paper_id, follow_id, arrived_at, state, score) " +
                    "VALUES ('2403.09999', 1, 5, 'new', 0.7)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 16, true, MIGRATION_15_16)

        db.query(
            "SELECT state, score, digested_at FROM inbox_items WHERE paper_id = '2403.09999'",
        ).use { c ->
            c.moveToFirst()
            assertEquals("new", c.getString(0), "the row survives untouched")
            assertEquals(0.7, c.getDouble(1), 0.0)
            assertTrue(c.isNull(2), "digested_at backfills NULL — never digested, the pre-P-Ambient meaning")
        }
        db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'inbox_items'",
        ).use { c ->
            c.moveToFirst()
            assertTrue("`digested_at` INTEGER" in c.getString(0), c.getString(0))
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-15-16-test"
    }
}
