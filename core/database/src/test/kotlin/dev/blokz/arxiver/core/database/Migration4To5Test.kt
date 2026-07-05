package dev.blokz.arxiver.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.blokz.arxiver.core.database.migration.MIGRATION_4_5
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v4 → v5 migration (P-Tools PT.0). `runMigrationsAndValidate` recreates the v4 schema from
 * `4.json`, applies [MIGRATION_4_5], and asserts the result matches the committed `5.json`
 * (identity hash) — so the hand-written CREATE TABLE / ALTER SQL can't drift from the generated
 * schema. Also proves `tools_enabled` defaults 0 on a pre-existing session and that a
 * `tool_invocations` row cascades when its assistant message is deleted (the ephemeral-audit + no
 * dangling-tool guarantee at the storage layer).
 */
@RunWith(RobolectricTestRunner::class)
class Migration4To5Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ArxiverDatabase::class.java,
        )

    @Test
    fun migrate4To5_addsToolsEnabledAndToolInvocations_cascades_andValidatesSchema() {
        helper.createDatabase(TEST_DB, 4).apply {
            // v4 chat_sessions carries pinned + title but NOT tools_enabled.
            execSQL(
                "INSERT INTO chat_sessions " +
                    "(id, scope, scope_id, provider_id, created_at, last_message_at, pinned, title) " +
                    "VALUES (1, 'PAPER', '2401.00001', 'CLAUDE', 1, 1, 0, NULL)",
            )
            execSQL(
                "INSERT INTO chat_messages (id, session_id, role, content, status, created_at) " +
                    "VALUES (10, 1, 'assistant', 'answer', 'complete', 1)",
            )
            close()
        }

        // Identity-hash gate: throws if MIGRATION_4_5's SQL doesn't reproduce 5.json exactly.
        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        // New consent column defaults 0 on the pre-existing session.
        db.query("SELECT tools_enabled FROM chat_sessions WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }

        // The helper opens with foreign_keys OFF — arm it so the CASCADE actually fires.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            "INSERT INTO tool_invocations " +
                "(message_id, tool_name, query, result_summary, egress, ordinal, created_at) " +
                "VALUES (10, 'echo', 'hi', 'hi', 0, 0, 1)",
        )
        db.query("SELECT COUNT(*) FROM tool_invocations").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }

        // Deleting the owning assistant message cascades the tool row away.
        db.execSQL("DELETE FROM chat_messages WHERE id = 10")
        db.query("SELECT COUNT(*) FROM tool_invocations").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0), "tool_invocations cascades on assistant-message delete")
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-4-5-test"
    }
}
