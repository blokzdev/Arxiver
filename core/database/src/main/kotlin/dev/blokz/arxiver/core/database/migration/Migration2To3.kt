package dev.blokz.arxiver.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v2 → v3 (P2.2): add the chat-history store (`chat_sessions` + `chat_messages`)
 * for grounded Q&A (SPEC-DATA chat-history). Additive only — no existing table is
 * touched. SQL mirrors Room's generated `createSql` (kept in sync with
 * schemas/.../3.json).
 */
val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `chat_sessions` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`scope` TEXT NOT NULL, " +
                    "`scope_id` TEXT NOT NULL, " +
                    "`provider_id` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "`last_message_at` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_chat_sessions_scope_scope_id` " +
                    "ON `chat_sessions` (`scope`, `scope_id`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `chat_messages` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`session_id` INTEGER NOT NULL, " +
                    "`role` TEXT NOT NULL, " +
                    "`content` TEXT NOT NULL, " +
                    "`status` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`session_id`) REFERENCES `chat_sessions`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_chat_messages_session_id` " +
                    "ON `chat_messages` (`session_id`)",
            )
        }
    }
