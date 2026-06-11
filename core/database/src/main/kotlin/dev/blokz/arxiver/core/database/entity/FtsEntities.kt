package dev.blokz.arxiver.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4

/**
 * External-content FTS4 index over papers (SPEC-DATA §3). Room generates and
 * maintains the sync triggers against [PaperEntity].
 */
@Fts4(contentEntity = PaperEntity::class)
@Entity(tableName = "papers_fts")
data class PaperFtsEntity(
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "abstract") val abstract: String,
    @ColumnInfo(name = "authors_line") val authorsLine: String,
)

/** External-content FTS4 index over note bodies. */
@Fts4(contentEntity = NoteEntity::class)
@Entity(tableName = "notes_fts")
data class NoteFtsEntity(
    @ColumnInfo(name = "content") val content: String,
)
