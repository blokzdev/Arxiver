package dev.blokz.arxiver.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.blokz.arxiver.core.database.migration.MIGRATION_2_3
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v2 → v3 migration (P2.2). `runMigrationsAndValidate` recreates the v2 schema
 * from `2.json`, applies [MIGRATION_2_3], and asserts the result matches `3.json`
 * (identity hash) — so the hand-written SQL can't drift from the generated schema.
 */
@RunWith(RobolectricTestRunner::class)
class Migration2To3Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ArxiverDatabase::class.java,
        )

    @Test
    fun migrate2To3_addsChatTables_andValidatesSchema() {
        helper.createDatabase(TEST_DB, 2).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        db.query("SELECT COUNT(*) FROM chat_sessions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM chat_messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-2-3-test"
    }
}
