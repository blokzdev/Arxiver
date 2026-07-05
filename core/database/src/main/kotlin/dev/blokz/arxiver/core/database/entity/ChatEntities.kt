package dev.blokz.arxiver.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * SPEC-DATA chat-history (P2.2) — a chat session grounded in a scope. `scope` is
 * `PAPER` (per-paper Ask) or `COLLECTION` (KB chat over a collection's papers);
 * `scope_id` holds the paper id or the collection id as text. `provider_id` is a
 * `ProviderId` name — never a key. Chat history is local conversation: excluded
 * from the automated/importable exports/backups (`BackupManager`/`LibraryExporter`)
 * — red line. (A user-initiated one-shot share-out of a single conversation as
 * Markdown via the OS share sheet is a different, explicit action — P-Rich R4.)
 */
@Entity(
    tableName = "chat_sessions",
    indices = [Index(value = ["scope", "scope_id"])],
)
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "scope") val scope: String,
    @ColumnInfo(name = "scope_id") val scopeId: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_message_at") val lastMessageAt: Long,
    // P-Chat PC.4. defaultValue="0" is REQUIRED — it must byte-match the migration's
    // `ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0` or Room's identity hash rejects the open.
    @ColumnInfo(name = "pinned", defaultValue = "0") val pinned: Boolean = false,
    // Nullable, no default: null = derive the label (paper title / collection name) as today.
    @ColumnInfo(name = "title") val title: String? = null,
    // P-Tools PT.0: per-conversation consent to attach EXTERNAL tools (the agentic tool loop).
    // Local tools (search_my_library) are consent-free. defaultValue="0" is REQUIRED to byte-match
    // the migration's `ADD COLUMN tools_enabled INTEGER NOT NULL DEFAULT 0` (identity-hash gate).
    @ColumnInfo(name = "tools_enabled", defaultValue = "0") val toolsEnabled: Boolean = false,
) {
    companion object {
        const val SCOPE_PAPER = "PAPER"
        const val SCOPE_COLLECTION = "COLLECTION"
    }
}

/**
 * P-Tools PT.0 — one executed tool step of an agentic assistant turn (SPEC-P-TOOLS §8).
 * Tool calls are ASSISTANT-authored intermediate steps of ONE logical turn, so they FK to the
 * single assistant [ChatMessageEntity] rather than becoming new `chat_messages` roles (which would
 * corrupt the history re-feed + the recents snippet subquery). Rows are **ephemeral-for-context**:
 * `prepare()` never replays them into a later turn — they exist for the inline activity-log render
 * (PT.1) + audit. [query] and [resultSummary] can carry model-derived text, so this table is walled
 * off the importable backup (the six-field allowlist DTO + the extended forbidden-name test).
 * [egress] records whether the call left the device (false for local tools like search_my_library).
 */
@Entity(
    tableName = "tool_invocations",
    foreignKeys = [
        ForeignKey(
            entity = ChatMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("message_id")],
)
data class ToolInvocationEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "tool_name") val toolName: String,
    @ColumnInfo(name = "query") val query: String,
    @ColumnInfo(name = "result_summary") val resultSummary: String,
    @ColumnInfo(name = "egress") val egress: Boolean,
    @ColumnInfo(name = "ordinal") val ordinal: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * One turn in a [ChatSessionEntity]. `status` tracks streaming: an assistant turn
 * is `incomplete` while tokens arrive, `complete` on a clean finish, or `error`
 * if the stream failed — so a cancelled/failed long answer survives in history.
 */
@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("session_id")],
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        const val STATUS_COMPLETE = "complete"
        const val STATUS_INCOMPLETE = "incomplete"
        const val STATUS_ERROR = "error"
    }
}
