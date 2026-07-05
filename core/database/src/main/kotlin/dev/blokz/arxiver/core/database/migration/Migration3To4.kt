package dev.blokz.arxiver.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v3 → v4 (P-Chat PC.4): chat-session pin + custom title, plus a one-shot repair of the
 * crash-artifact ghost turns a mid-stream process death leaves behind.
 *
 * (a)/(b) additive columns on `chat_sessions`: `pinned` (INTEGER NOT NULL DEFAULT 0 — the
 *   ALTER literal MUST byte-match the entity's `@ColumnInfo(defaultValue = "0")` or Room's
 *   identity hash rejects the open) and `title` (nullable TEXT, no default — null = derive
 *   the label as today).
 * (c) DML repair (NOT destructive migration — no DROP / table rebuild): delete the exact
 *   zero-information ghost (assistant + incomplete + empty content). The FULL triple
 *   predicate is load-bearing — dropping the `content = ''` conjunct would eat a real
 *   partial answer; dropping `status = 'incomplete'` would eat an errored-empty turn that
 *   PC.3 surfaces as the user-question snippet fallback. A one-shot clean of the lie at
 *   rest; PC.0's hydrate filter + PC.3's snippet `content != ''` predicate guard recurrence.
 */
val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `title` TEXT")
            db.execSQL(
                "DELETE FROM chat_messages " +
                    "WHERE role = 'assistant' AND status = 'incomplete' AND content = ''",
            )
        }
    }
