package dev.blokz.arxiver.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.blokz.arxiver.core.database.migration.MIGRATION_10_11
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * v10 → v11 migration (P-FeedPolish PFP.3). `runMigrationsAndValidate` recreates v10 from `10.json`, applies
 * [MIGRATION_10_11], and asserts the result matches the committed `11.json` (identity hash) — so the hand-written
 * `ALTER … ADD COLUMN empty_sync_streak INTEGER NOT NULL DEFAULT 0` byte-matches Room's `@ColumnInfo`. Also proves
 * an existing follow's data survives and the new column back-fills 0 (additive, non-destructive).
 */
@RunWith(RobolectricTestRunner::class)
class Migration10To11Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ArxiverDatabase::class.java,
        )

    @Test
    fun migrate10To11_addsEmptySyncStreak_defaultZero_andValidatesSchema() {
        helper.createDatabase(TEST_DB, 10).apply {
            // A v10 follow row (no empty_sync_streak column yet) — its data must survive the upgrade untouched.
            execSQL(
                "INSERT INTO follows (type, value, label, created_at, last_synced_at, enabled, origin) " +
                    "VALUES ('category', 'cs.LG', 'ML', 0, 42, 1, 'biorxiv')",
            )
            close()
        }

        // Identity-hash gate: throws if MIGRATION_10_11 doesn't reproduce 11.json exactly (column + defaultValue).
        val db = helper.runMigrationsAndValidate(TEST_DB, 11, true, MIGRATION_10_11)

        db.query("SELECT last_synced_at, empty_sync_streak FROM follows WHERE value = 'cs.LG'").use { c ->
            assertEquals(1, c.count, "the pre-existing follow survives the additive migration")
            c.moveToFirst()
            assertEquals(42, c.getLong(0), "its last_synced_at is preserved")
            assertEquals(0, c.getInt(1), "the new empty_sync_streak back-fills 0")
        }
        // The new column is writable (the health streak accumulates into it post-migration).
        db.execSQL("UPDATE follows SET empty_sync_streak = 3 WHERE value = 'cs.LG'")
        db.query("SELECT empty_sync_streak FROM follows WHERE value = 'cs.LG'").use { c ->
            c.moveToFirst()
            assertEquals(3, c.getInt(0))
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-10-11-test"
    }
}
