package dev.blokz.arxiver.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.blokz.arxiver.core.database.migration.MIGRATION_5_6
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v5 → v6 migration (P-Tools PT.2). `runMigrationsAndValidate` recreates the v5 schema from `5.json`,
 * applies [MIGRATION_5_6], and asserts the result matches the committed `6.json` (identity hash) — so
 * the hand-written ALTER can't drift from the generated schema. Also the **anti-repurpose guarantee**:
 * a pre-existing v5 "library-on" session (`tools_enabled = 1`) gets `web_search_enabled` defaulting to
 * 0 — its opt-in to library search is NOT silently converted into an external-egress opt-in.
 */
@RunWith(RobolectricTestRunner::class)
class Migration5To6Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ArxiverDatabase::class.java,
        )

    @Test
    fun migrate5To6_addsWebSearchEnabled_defaultsZero_andValidatesSchema() {
        helper.createDatabase(TEST_DB, 5).apply {
            // A v5 session that already opted INTO library search (tools_enabled = 1), NOT web search.
            execSQL(
                "INSERT INTO chat_sessions " +
                    "(id, scope, scope_id, provider_id, created_at, last_message_at, pinned, title, tools_enabled) " +
                    "VALUES (1, 'PAPER', '2401.00001', 'CLAUDE', 1, 1, 0, NULL, 1)",
            )
            close()
        }

        // Identity-hash gate: throws if MIGRATION_5_6's SQL doesn't reproduce 6.json exactly.
        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6)

        db.query("SELECT tools_enabled, web_search_enabled FROM chat_sessions WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0), "library consent (tools_enabled) is preserved")
            assertEquals(0, c.getInt(1), "web consent defaults 0 — library-on is NOT converted to web-on")
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-5-6-test"
    }
}
