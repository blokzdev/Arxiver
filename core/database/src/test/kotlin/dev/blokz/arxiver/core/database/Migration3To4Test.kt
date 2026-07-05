package dev.blokz.arxiver.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.blokz.arxiver.core.database.migration.MIGRATION_3_4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v3 → v4 migration (P-Chat PC.4). `runMigrationsAndValidate` recreates the v3 schema from
 * `3.json`, applies [MIGRATION_3_4], and asserts the result matches the committed `4.json`
 * (identity hash) — so the hand-written ALTER SQL can't drift from the generated schema.
 * Also proves the ghost sweep fires on exactly the (assistant, incomplete, empty) shape and
 * spares every other row byte-identical.
 */
@RunWith(RobolectricTestRunner::class)
class Migration3To4Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ArxiverDatabase::class.java,
        )

    @Test
    fun migrate3To4_addsPinnedTitle_sweepsGhost_andValidatesSchema() {
        helper.createDatabase(TEST_DB, 3).apply {
            // Parent session first (FK order); all messages hang off session_id = 1.
            execSQL(
                "INSERT INTO chat_sessions (id, scope, scope_id, provider_id, created_at, last_message_at) " +
                    "VALUES (1, 'PAPER', '2401.00001', 'CLAUDE', 1, 1)",
            )

            fun msg(
                id: Int,
                role: String,
                content: String,
                status: String,
            ) = execSQL(
                "INSERT INTO chat_messages (id, session_id, role, content, status, created_at) " +
                    "VALUES ($id, 1, '$role', '$content', '$status', $id)",
            )
            msg(1, "assistant", "", "incomplete") // the ghost — must be deleted
            msg(2, "assistant", "partial answer", "incomplete") // keeper: non-empty partial
            msg(3, "assistant", "", "error") // keeper: errored-empty snippet fallback
            msg(4, "user", "q", "complete") // keeper
            msg(5, "assistant", "done", "complete") // keeper
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        // The exact ghost predicate fired — a positive proof, not a coincidental count.
        db.query(
            "SELECT COUNT(*) FROM chat_messages WHERE role = 'assistant' AND status = 'incomplete' AND content = ''",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        // Both conjuncts spare correctly: the one surviving empty row is the errored one.
        db.query("SELECT status FROM chat_messages WHERE content = ''").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("error", c.getString(0))
            assertTrue(!c.moveToNext(), "only the errored-empty row survives with empty content")
        }
        // The non-empty partial is untouched byte-identical.
        db.query("SELECT content FROM chat_messages WHERE id = 2").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("partial answer", c.getString(0))
        }
        // 5 seeded − 1 ghost = 4 survivors.
        db.query("SELECT COUNT(*) FROM chat_messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(4, c.getInt(0))
        }
        // New columns default correctly on the pre-existing session.
        db.query("SELECT pinned, title FROM chat_sessions WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
            assertTrue(c.isNull(1), "title defaults to NULL (derive the label as today)")
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-3-4-test"
    }
}
