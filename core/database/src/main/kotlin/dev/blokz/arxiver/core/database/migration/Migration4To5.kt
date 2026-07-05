package dev.blokz.arxiver.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v4 → v5 (P-Tools PT.0): the agentic tool loop's persistence surface — additive only.
 *
 * (a) `tool_invocations` — one row per executed tool step, FK'd to the single assistant
 *   `chat_messages` row (ON DELETE CASCADE) so tool audit rows die with their turn. Column order,
 *   types, the FK clause, and the index name MUST byte-match Room's generated schema for
 *   `ToolInvocationEntity` or `runMigrationsAndValidate` rejects the open (identity-hash gate).
 * (b) `chat_sessions.tools_enabled` — per-conversation external-tool consent. `INTEGER NOT NULL
 *   DEFAULT 0` MUST byte-match the entity's `@ColumnInfo(defaultValue = "0")` (same discipline as
 *   `pinned` in v4). Nothing destructive — no DROP, no table rebuild.
 */
val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `tool_invocations` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`message_id` INTEGER NOT NULL, " +
                    "`tool_name` TEXT NOT NULL, " +
                    "`query` TEXT NOT NULL, " +
                    "`result_summary` TEXT NOT NULL, " +
                    "`egress` INTEGER NOT NULL, " +
                    "`ordinal` INTEGER NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`message_id`) REFERENCES `chat_messages`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_tool_invocations_message_id` " +
                    "ON `tool_invocations` (`message_id`)",
            )
            db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `tools_enabled` INTEGER NOT NULL DEFAULT 0")
        }
    }
