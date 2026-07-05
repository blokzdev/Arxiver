package dev.blokz.arxiver.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v5 → v6 (P-Tools PT.2): a SECOND per-conversation tool-consent column — additive only.
 *
 * `web_search_enabled` gates the EXTERNAL web tools (search_arxiv / get_paper / import_to_library),
 * kept DISTINCT from `tools_enabled` (the local library-search gate, PT.1) so a user can opt into
 * library search without egressing queries to arXiv. Reusing `tools_enabled` would have silently
 * converted every existing "library-on" conversation into "web-on" — a consent red line. `INTEGER
 * NOT NULL DEFAULT 0` MUST byte-match the entity's `@ColumnInfo(defaultValue = "0")` (identity-hash
 * gate). No DROP / rebuild.
 */
val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `web_search_enabled` INTEGER NOT NULL DEFAULT 0")
        }
    }
