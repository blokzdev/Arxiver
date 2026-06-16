package dev.blokz.arxiver.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.blokz.arxiver.core.database.entity.ChatMessageEntity
import dev.blokz.arxiver.core.database.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

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

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY created_at, id")
    suspend fun messagesFor(sessionId: Long): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY created_at, id")
    fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>>

    /** Sessions for a paper or collection, most-recently-active first. */
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

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun sessionById(id: Long): ChatSessionEntity?

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)
}
