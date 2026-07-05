package dev.blokz.arxiver.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.entity.ChatMessageEntity
import dev.blokz.arxiver.core.database.entity.ChatSessionEntity
import dev.blokz.arxiver.core.database.entity.CollectionEntity
import dev.blokz.arxiver.core.database.entity.PaperEntity
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

    private fun paper(
        id: String,
        title: String,
    ) = PaperEntity(
        id = id,
        latestVersion = 1,
        title = title,
        abstract = "",
        publishedAt = 0,
        updatedAt = 0,
        primaryCategory = "cs.LG",
        authorsLine = "",
        comment = null,
        journalRef = null,
        doi = null,
        pdfUrl = "",
        citationCount = null,
        s2PaperId = null,
        source = "arxiv",
        fetchedAt = 0,
        embeddedAt = null,
        citationsSyncedAt = null,
    )

    private fun msg(
        sessionId: Long,
        role: String,
        content: String,
        status: String,
        createdAt: Long,
    ) = ChatMessageEntity(
        sessionId = sessionId,
        role = role,
        content = content,
        status = status,
        createdAt = createdAt,
    )

    // --- P-Chat PC.3: observeSessionRows JOIN (scope label + snippet in SQL, no N+1) ---

    @Test
    fun `observeSessionRows joins a paper title and its latest non-empty snippet`() =
        runTest {
            val dao = db.chatDao()
            db.paperDao().upsertPaper(paper("2401.00001", "Attention Is All You Need"))
            val sid = dao.insertSession(session(scopeId = "2401.00001"))
            dao.insertMessage(msg(sid, "user", "what is attention?", "complete", 1))
            dao.insertMessage(msg(sid, "assistant", "a weighted sum", "complete", 2))

            val row = dao.observeSessionRows().first().single()
            assertEquals("Attention Is All You Need", row.paperTitle)
            assertEquals(null, row.collectionName)
            assertEquals("a weighted sum", row.snippet)
        }

    @Test
    fun `observeSessionRows joins a collection name through the CAST`() =
        runTest {
            val dao = db.chatDao()
            val cid = db.libraryDao().createCollection(CollectionEntity(name = "Transformers", createdAt = 0))
            val sid = dao.insertSession(session(scope = ChatSessionEntity.SCOPE_COLLECTION, scopeId = cid.toString()))
            dao.insertMessage(msg(sid, "assistant", "collection answer", "complete", 1))

            val row = dao.observeSessionRows().first().single()
            assertEquals("Transformers", row.collectionName)
            assertEquals(null, row.paperTitle)
            assertEquals("collection answer", row.snippet)
        }

    @Test
    fun `observeSessionRows leaves labels null when the target was deleted`() =
        runTest {
            val dao = db.chatDao()
            dao.insertSession(session(scopeId = "gone.99999")) // no papers row
            dao.insertSession(session(scope = ChatSessionEntity.SCOPE_COLLECTION, scopeId = "404")) // no collection

            val rows = dao.observeSessionRows().first()
            assertTrue(rows.all { it.paperTitle == null && it.collectionName == null })
        }

    @Test
    fun `the snippet is the latest non-empty message - ghosts are skipped and id breaks a timestamp tie`() =
        runTest {
            val dao = db.chatDao()
            val sid = dao.insertSession(session())
            dao.insertMessage(msg(sid, "user", "question", "complete", 5))
            // Same created_at for the two assistant rows: the higher autoincrement id (the real
            // answer, inserted last) must win over an earlier empty-ghost row at the same ms.
            dao.insertMessage(msg(sid, "assistant", "", "incomplete", 6)) // ghost
            dao.insertMessage(msg(sid, "assistant", "the real answer", "complete", 6))

            assertEquals("the real answer", dao.observeSessionRows().first().single().snippet)
        }

    @Test
    fun `the snippet is null when the session has only an empty ghost message`() =
        runTest {
            val dao = db.chatDao()
            val sid = dao.insertSession(session())
            dao.insertMessage(msg(sid, "assistant", "", "incomplete", 1)) // the ghost, only row

            assertEquals(null, dao.observeSessionRows().first().single().snippet)
        }

    @Test
    fun `an errored first turn falls back to the user question as the snippet`() =
        runTest {
            val dao = db.chatDao()
            val sid = dao.insertSession(session())
            dao.insertMessage(msg(sid, "user", "my question", "complete", 1))
            dao.insertMessage(msg(sid, "assistant", "", "error", 2)) // failed answer, empty

            assertEquals("my question", dao.observeSessionRows().first().single().snippet)
        }

    @Test
    fun `observeSessionRows orders by last activity across scopes`() =
        runTest {
            val dao = db.chatDao()
            dao.insertSession(session(scopeId = "p1").copy(lastMessageAt = 10))
            val newer = dao.insertSession(session(scope = ChatSessionEntity.SCOPE_COLLECTION, scopeId = "7"))
            dao.touchSession(newer, 99)

            assertEquals(newer, dao.observeSessionRows().first().first().session.id)
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

    // --- P-Chat PC.4: pinned + title (schema v4) ---

    @Test
    fun `observeSessionRows floats a pinned session above a fresher unpinned one`() =
        runTest {
            val dao = db.chatDao()
            val pinned = dao.insertSession(session(scopeId = "p1").copy(lastMessageAt = 10))
            dao.insertSession(session(scopeId = "p2").copy(lastMessageAt = 99)) // fresher, unpinned
            dao.setPinned(pinned, true)

            val first = dao.observeSessionRows().first().first()
            assertEquals(pinned, first.session.id)
            assertTrue(first.session.pinned)
        }

    @Test
    fun `observeSessions ignores pinned so a paper sheet resumes the genuinely most-recent`() =
        runTest {
            val dao = db.chatDao()
            val older = dao.insertSession(session(scopeId = "p1").copy(lastMessageAt = 10))
            val newer = dao.insertSession(session(scopeId = "p1").copy(lastMessageAt = 99))
            dao.setPinned(older, true) // pinning the OLDER one must NOT change resume order

            // The invariant AskViewModel MostRecentFor depends on — never pinned-first here.
            assertEquals(newer, dao.observeSessions(ChatSessionEntity.SCOPE_PAPER, "p1").first().first().id)
        }

    @Test
    fun `setPinned round-trips`() =
        runTest {
            val dao = db.chatDao()
            val sid = dao.insertSession(session())
            dao.setPinned(sid, true)
            assertTrue(dao.observeSessionRows().first().single { it.session.id == sid }.session.pinned)
            dao.setPinned(sid, false)
            assertTrue(!dao.observeSessionRows().first().single { it.session.id == sid }.session.pinned)
        }

    @Test
    fun `renameSession sets and clears a custom title`() =
        runTest {
            val dao = db.chatDao()
            val sid = dao.insertSession(session())
            dao.renameSession(sid, "My title")
            assertEquals("My title", dao.observeSessionRows().first().single().session.title)
            dao.renameSession(sid, null)
            assertEquals(null, dao.observeSessionRows().first().single().session.title)
        }
}
