package dev.blokz.arxiver.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.blokz.arxiver.core.database.migration.MIGRATION_16_17
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v16 → v17 (P-Read): the additive `reading_positions` table. The identity-hash gate
 * (`runMigrationsAndValidate`) proves the transcribed DDL matches Room's generated `17.json`; then a row
 * inserts + reads back (no FK to `papers`, so an orphan row — the paperless-reader-open case — inserts cleanly).
 */
@RunWith(RobolectricTestRunner::class)
class Migration16To17Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ArxiverDatabase::class.java,
        )

    @Test
    fun migrate16To17_createsReadingPositions_rowRoundTrips() {
        helper.createDatabase(TEST_DB, 16).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 17, true, MIGRATION_16_17)

        db.execSQL(
            "INSERT INTO reading_positions " +
                "(paper_id, surface, version, anchor_id, offset_px, fraction, page_index, finished, updated_at) " +
                "VALUES ('2403.09999', 'html', 2, 'S2.SS1', 340, 0.41, NULL, 0, 100)",
        )
        db.query(
            "SELECT surface, fraction, finished FROM reading_positions WHERE paper_id = '2403.09999'",
        ).use { c ->
            c.moveToFirst()
            assertEquals("html", c.getString(0))
            assertEquals(0.41, c.getDouble(1), 1e-6)
            assertEquals(0, c.getInt(2), "finished defaults to not-finished")
        }
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_reading_positions_updated_at'",
        ).use { c ->
            assertTrue(c.moveToFirst(), "the updated_at index exists")
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-16-17-test"
    }
}
