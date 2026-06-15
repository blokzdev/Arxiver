package dev.blokz.arxiver.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.blokz.arxiver.core.database.migration.MIGRATION_1_2
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * First real Room migration (v1 → v2, P2.1). `runMigrationsAndValidate` recreates
 * the v1 schema from the committed `1.json`, applies [MIGRATION_1_2], and asserts
 * the result matches `2.json` (identity hash) — so the hand-written migration SQL
 * can't drift from the generated schema. Runs under Robolectric in CI.
 */
@RunWith(RobolectricTestRunner::class)
class Migration1To2Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ArxiverDatabase::class.java,
        )

    @Test
    fun migrate1To2_addsChunkTables_andValidatesSchema() {
        helper.createDatabase(TEST_DB, 1).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        db.query("SELECT COUNT(*) FROM chunk_embeddings").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        // The external-content FTS table is queryable post-migration.
        db.query("SELECT COUNT(*) FROM chunk_fts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-1-2-test"
    }
}
