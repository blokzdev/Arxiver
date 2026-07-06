package dev.blokz.arxiver.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.blokz.arxiver.core.database.migration.MIGRATION_7_8
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v7 → v8 migration (P-Feeds PF.2). `runMigrationsAndValidate` recreates v7 from `7.json`, applies
 * [MIGRATION_7_8], and asserts the result matches the committed `8.json` (identity hash) — so the
 * hand-written `ALTER` + index rebuild can't drift from the generated schema. Also: the
 * **zero-rows-re-keyed guarantee** (an existing arXiv follow's `id` PK is byte-unchanged, `origin` backfills
 * to `'arxiv'`) AND the **new-index** behaviour — the same `(type, value)` may now coexist across origins.
 */
@RunWith(RobolectricTestRunner::class)
class Migration7To8Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ArxiverDatabase::class.java,
        )

    @Test
    fun migrate7To8_addsOrigin_arxivRowUnchanged_widensUniqueIndex_andValidatesSchema() {
        helper.createDatabase(TEST_DB, 7).apply {
            // A v7 `follows` row (no origin column pre-migration).
            execSQL(
                "INSERT INTO follows (id, type, value, label, created_at, last_synced_at, enabled) " +
                    "VALUES (1, 'category', 'cs.LG', 'CS - Machine Learning', 0, NULL, 1)",
            )
            close()
        }

        // Identity-hash gate: throws if MIGRATION_7_8's SQL doesn't reproduce 8.json exactly.
        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)

        db.query("SELECT id, type, value, origin FROM follows").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0), "existing follow id is byte-unchanged (NOT re-keyed)")
            assertEquals("cs.LG", c.getString(2))
            assertEquals("arxiv", c.getString(3), "origin backfills to 'arxiv' in place")
            assertEquals(1, c.count, "exactly one row — none dropped or duplicated")
        }

        // The widened unique index (type, value, origin) lets the same (type, value) coexist across origins.
        db.execSQL(
            "INSERT INTO follows (id, type, value, label, created_at, enabled, origin) " +
                "VALUES (2, 'category', 'cs.LG', 'CS - Machine Learning', 0, 1, 'biorxiv')",
        )
        db.query("SELECT COUNT(*) FROM follows WHERE type = 'category' AND value = 'cs.LG'").use { c ->
            c.moveToFirst()
            assertEquals(2, c.getInt(0), "same (type, value) coexists across origins under the new index")
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-7-8-test"
    }
}
