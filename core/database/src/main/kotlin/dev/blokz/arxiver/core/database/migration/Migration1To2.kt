package dev.blokz.arxiver.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2 (P2.1): add the chunk-embedding store + its external-content FTS index
 * for on-device RAG retrieval (SPEC-SEARCH §8 / SPEC-DATA §4). Additive only — no
 * existing table is touched (destructive migration is a red line). The SQL mirrors
 * Room's generated `createSql` for `ChunkEmbeddingEntity` / `ChunkFtsEntity`
 * (kept in sync with schemas/.../2.json), including the FTS sync triggers Room
 * installs for an external-content FTS4 table.
 */
val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `chunk_embeddings` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`paper_id` TEXT NOT NULL, " +
                    "`chunk_text` TEXT NOT NULL, " +
                    "`vector` BLOB NOT NULL, " +
                    "`model` TEXT NOT NULL, " +
                    "`dim` INTEGER NOT NULL, " +
                    "`source_kind` TEXT NOT NULL, " +
                    "`ordinal` INTEGER NOT NULL, " +
                    "`embedded_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`paper_id`) REFERENCES `papers`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_chunk_embeddings_paper_id` " +
                    "ON `chunk_embeddings` (`paper_id`)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_chunk_embeddings_paper_id_source_kind_ordinal` " +
                    "ON `chunk_embeddings` (`paper_id`, `source_kind`, `ordinal`)",
            )
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS `chunk_fts` USING FTS4(" +
                    "`chunk_text` TEXT NOT NULL, content=`chunk_embeddings`)",
            )
            // External-content FTS4 sync triggers (mirror Room's generated set).
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chunk_fts_BEFORE_UPDATE " +
                    "BEFORE UPDATE ON `chunk_embeddings` BEGIN " +
                    "DELETE FROM `chunk_fts` WHERE `docid`=OLD.`rowid`; END",
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chunk_fts_BEFORE_DELETE " +
                    "BEFORE DELETE ON `chunk_embeddings` BEGIN " +
                    "DELETE FROM `chunk_fts` WHERE `docid`=OLD.`rowid`; END",
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chunk_fts_AFTER_UPDATE " +
                    "AFTER UPDATE ON `chunk_embeddings` BEGIN " +
                    "INSERT INTO `chunk_fts`(`docid`, `chunk_text`) " +
                    "VALUES (NEW.`rowid`, NEW.`chunk_text`); END",
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_chunk_fts_AFTER_INSERT " +
                    "AFTER INSERT ON `chunk_embeddings` BEGIN " +
                    "INSERT INTO `chunk_fts`(`docid`, `chunk_text`) " +
                    "VALUES (NEW.`rowid`, NEW.`chunk_text`); END",
            )
        }
    }
