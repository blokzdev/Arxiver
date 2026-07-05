package dev.blokz.arxiver.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import dev.blokz.arxiver.core.database.entity.ChatMessageEntity
import dev.blokz.arxiver.core.database.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * A chat-history list row (P-Chat PC.3): a session with its scope label and latest non-empty
 * message resolved IN SQL — replacing the per-row `paperById` N+1. [paperTitle]/[collectionName]
 * are null when the target was deleted; [snippet] is null when the session has no non-empty
 * message yet (a Done-with-no-text turn persists `content = ''` and is skipped).
 */
data class ChatSessionRow(
    @Embedded val session: ChatSessionEntity,
    val paperTitle: String?,
    val collectionName: String?,
    val snippet: String?,
)

/** Chat-history store for grounded Q&A (SPEC-DATA chat-history, P2.2). */
@Dao
interface ChatDao {
    @Insert
    suspend fun insertSession(session: ChatSessionEntity): Long

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("UPDATE chat_messages SET content = :content, status = :status WHERE id = :id")
    suspend fun updateMessage(
        id: Long,
        content: String,
        status: String,
    )

    @Query("UPDATE chat_sessions SET last_message_at = :at WHERE id = :id")
    suspend fun touchSession(
        id: Long,
        at: Long,
    )

    /** Pin/unpin a session (P-Chat PC.4; consumed by the PC.5 UI). */
    @Query("UPDATE chat_sessions SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(
        id: Long,
        pinned: Boolean,
    )

    /** Set/clear a custom title (null = derive the label; consumed by the PC.5 rename UI). */
    @Query("UPDATE chat_sessions SET title = :title WHERE id = :id")
    suspend fun renameSession(
        id: Long,
        title: String?,
    )

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY created_at, id")
    suspend fun messagesFor(sessionId: Long): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY created_at, id")
    fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>>

    /**
     * Sessions for a paper or collection, most-recently-active first. **NOT pinned-first:**
     * AskViewModel's MostRecentFor resume must return the genuinely latest session for the
     * scope — ordering pinned DESC here would resume a stale pinned session in the paper
     * sheet (pinned by a DAO test; PC.4).
     */
    @Query(
        """
        SELECT * FROM chat_sessions
        WHERE scope = :scope AND scope_id = :scopeId
        ORDER BY last_message_at DESC
        """,
    )
    fun observeSessions(
        scope: String,
        scopeId: String,
    ): Flow<List<ChatSessionEntity>>

    /** Every session across all scopes, most-recently-active first (chat-history list). */
    @Query("SELECT * FROM chat_sessions ORDER BY last_message_at DESC")
    fun observeAllSessions(): Flow<List<ChatSessionEntity>>

    /**
     * The promoted chat-history list (P-Chat PC.3): every session with its scope label and
     * latest **non-empty** message resolved in one query — no per-row `paperById`. The paper
     * join is a direct id match (`papers.id` is TEXT); the collection join needs `CAST(c.id AS
     * TEXT)` because `chat_sessions.scope_id` is TEXT while `collections.id` is INTEGER. The
     * snippet subquery filters `content != ''` (a Done-with-no-text turn is skipped, never a
     * ghost bubble) and tie-breaks on `id DESC` so the assistant answer (higher autoincrement
     * id) wins when a user + assistant turn share a `created_at` ms.
     */
    @Query(
        """
        SELECT s.*,
               p.title AS paperTitle,
               c.name AS collectionName,
               (SELECT m.content FROM chat_messages m
                 WHERE m.session_id = s.id AND m.content != ''
                 ORDER BY m.created_at DESC, m.id DESC LIMIT 1) AS snippet
        FROM chat_sessions s
        LEFT JOIN papers p ON s.scope = 'PAPER' AND s.scope_id = p.id
        LEFT JOIN collections c ON s.scope = 'COLLECTION' AND s.scope_id = CAST(c.id AS TEXT)
        ORDER BY s.pinned DESC, s.last_message_at DESC
        """,
    )
    fun observeSessionRows(): Flow<List<ChatSessionRow>>

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun sessionById(id: Long): ChatSessionEntity?

    /** Observe one session for a live title/pin (P-Chat PC.5); emits null if deleted elsewhere. */
    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    fun observeSession(id: Long): Flow<ChatSessionEntity?>

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)
}
