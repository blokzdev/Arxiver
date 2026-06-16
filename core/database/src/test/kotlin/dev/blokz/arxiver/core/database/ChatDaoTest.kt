package dev.blokz.arxiver.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.entity.ChatMessageEntity
import dev.blokz.arxiver.core.database.entity.ChatSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ChatDaoTest {
    private lateinit var db: ArxiverDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun session(
        scope: String = ChatSessionEntity.SCOPE_PAPER,
        scopeId: String = "2401.00001",
    ) = ChatSessionEntity(scope = scope, scopeId = scopeId, providerId = "CLAUDE", createdAt = 1, lastMessageAt = 1)

    @Test
    fun `messages round-trip in chronological order`() =
        runTest {
            val dao = db.chatDao()
            val sid = dao.insertSession(session())
            dao.insertMessage(
                ChatMessageEntity(sessionId = sid, role = "user", content = "q", status = "complete", createdAt = 2),
            )
            val aid =
                dao.insertMessage(
                    ChatMessageEntity(
                        sessionId = sid,
                        role = "assistant",
                        content = "",
                        status = "incomplete",
                        createdAt = 3,
                    ),
                )
            dao.updateMessage(aid, "answer", "complete")

            val rows = dao.messagesFor(sid)
            assertEquals(listOf("q", "answer"), rows.map { it.content })
            assertEquals("complete", rows.last().status)
        }

    @Test
    fun `sessions are scoped and ordered by last activity`() =
        runTest {
            val dao = db.chatDao()
            val s1 = dao.insertSession(session(scopeId = "p1"))
            dao.insertSession(session(scopeId = "p2"))
            dao.touchSession(s1, 99)

            val forP1 = dao.observeSessions(ChatSessionEntity.SCOPE_PAPER, "p1").first()
            assertEquals(listOf(s1), forP1.map { it.id })
            assertEquals(99, forP1.single().lastMessageAt)
        }

    @Test
    fun `observeAllSessions returns every scope, most-recent first`() =
        runTest {
            val dao = db.chatDao()
            dao.insertSession(session(scope = ChatSessionEntity.SCOPE_PAPER, scopeId = "p1").copy(lastMessageAt = 10))
            val newer = dao.insertSession(session(scope = ChatSessionEntity.SCOPE_COLLECTION, scopeId = "7"))
            dao.touchSession(newer, 99)

            val all = dao.observeAllSessions().first()
            assertEquals(listOf(newer), all.take(1).map { it.id })
            assertEquals(2, all.size)
        }

    @Test
    fun `deleting a session cascades to its messages`() =
        runTest {
            val dao = db.chatDao()
            val sid = dao.insertSession(session())
            dao.insertMessage(
                ChatMessageEntity(sessionId = sid, role = "user", content = "q", status = "complete", createdAt = 2),
            )

            dao.deleteSession(sid)
            assertTrue(dao.messagesFor(sid).isEmpty())
            assertEquals(null, dao.sessionById(sid))
        }
}
