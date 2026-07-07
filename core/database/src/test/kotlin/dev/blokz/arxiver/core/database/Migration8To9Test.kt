package dev.blokz.arxiver.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.blokz.arxiver.core.database.migration.MIGRATION_8_9
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v8 → v9 migration (P4.0). `runMigrationsAndValidate` recreates v8 from `8.json`, applies
 * [MIGRATION_8_9], and asserts the result matches the committed `9.json` (identity hash) — so the
 * hand-written `CREATE TABLE`/index SQL can't drift from what Room generates for [PaperFeedbackEntity].
 * Also confirms the migration is **purely additive** (a pre-existing paper survives untouched) and
 * that the new `paper_feedback` table is usable afterwards.
 */
@RunWith(RobolectricTestRunner::class)
class Migration8To9Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ArxiverDatabase::class.java,
        )

    @Test
    fun migrate8To9_addsPaperFeedback_paperUnchanged_andValidatesSchema() {
        helper.createDatabase(TEST_DB, 8).apply {
            // A v8 `papers` row (paper_feedback does not exist yet at v8).
            execSQL(
                "INSERT INTO papers " +
                    "(id, latest_version, title, abstract, published_at, updated_at, primary_category, " +
                    "pdf_url, source, fetched_at) " +
                    "VALUES ('p1', 1, 'T', 'A', 0, 0, 'cs.LG', '', 'arxiv', 0)",
            )
            close()
        }

        // Identity-hash gate: throws if MIGRATION_8_9's SQL doesn't reproduce 9.json exactly.
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9)

        // Additive: the existing paper is byte-unchanged.
        db.query("SELECT id, title FROM papers").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("p1", c.getString(0), "existing paper survives the additive migration")
            assertEquals(1, c.count, "exactly one row — none dropped or duplicated")
        }

        // The new table exists and accepts a durable label.
        db.execSQL("INSERT INTO paper_feedback (paper_id, signal, source, created_at) VALUES ('p1', -1, 'dismiss', 7)")
        db.query("SELECT signal FROM paper_feedback WHERE paper_id = 'p1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(-1, c.getInt(0), "paper_feedback stores the dismiss label")
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-8-9-test"
    }
}
