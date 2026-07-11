package dev.blokz.arxiver.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.blokz.arxiver.core.database.migration.MIGRATION_14_15
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v14 → v15 (P5.5). Identity-hash gate + the sqlite_master DDL guard, and the semantic backfill invariant:
 * an existing fitted row migrates with `consecutive_null_fits = 0` — "current fit is fresh" — which is exactly
 * the pre-P5.5 behavior, so migrated and fresh installs behave identically at the hysteresis boundary.
 */
@RunWith(RobolectricTestRunner::class)
class Migration14To15Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ArxiverDatabase::class.java,
        )

    @Test
    fun migrate14To15_addsNullFitStreak_backfillsZero_preservesTheFittedRow() {
        helper.createDatabase(TEST_DB, 14).apply {
            // A fitted pre-v15 model row — must survive byte-identical with streak 0.
            execSQL(
                "INSERT INTO relevance_model (id, embedding_model, calibration_a, calibration_b, " +
                    "shrinkage_lambda, label_positives, label_negatives, fitted_at) " +
                    "VALUES (0, 'bge-small-en-v1.5-q8', 48.7068146312337, -20.181903855252, 0.3, 45, 40, 7)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, MIGRATION_14_15)

        db.query(
            "SELECT calibration_a, calibration_b, shrinkage_lambda, fitted_at, consecutive_null_fits " +
                "FROM relevance_model WHERE id = 0",
        ).use { c ->
            c.moveToFirst()
            assertEquals(48.7068146312337, c.getDouble(0), 0.0, "a survives byte-identical")
            assertEquals(-20.181903855252, c.getDouble(1), 0.0, "b survives byte-identical")
            assertEquals(0.3, c.getDouble(2), 0.0)
            assertEquals(7L, c.getLong(3))
            assertEquals(0, c.getInt(4), "backfill = 0: 'current fit is fresh', the pre-P5.5 semantics")
        }
        // The TableInfo blind spot: the added column's DDL must match Room's generated shape.
        db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'relevance_model'",
        ).use { c ->
            c.moveToFirst()
            val sql = c.getString(0)
            assertTrue("`consecutive_null_fits` INTEGER NOT NULL DEFAULT 0" in sql, sql)
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-14-15-test"
    }
}
