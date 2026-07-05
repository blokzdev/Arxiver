package dev.blokz.arxiver.feature.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.chat.ChatContextAssembler
import dev.blokz.arxiver.chat.ChatPreviewBuilder
import dev.blokz.arxiver.core.ai.AiKeyStore
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.core.ai.ProviderRegistry
import dev.blokz.arxiver.core.ai.ProviderResolver
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DefaultDispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.ChatSessionEntity
import dev.blokz.arxiver.core.database.entity.CollectionEntity
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.search.ChunkKeywordSource
import dev.blokz.arxiver.core.search.ChunkVectorSource
import dev.blokz.arxiver.core.search.RagRetriever
import dev.blokz.arxiver.core.search.RetrievalScope
import dev.blokz.arxiver.core.search.ScopedChunk
import dev.blokz.arxiver.data.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatHistoryViewModelTest {
    private lateinit var db: ArxiverDatabase

    private class FakeKeyStore : AiKeyStore {
        override fun put(
            provider: ProviderId,
            key: String,
        ) = Unit

        override fun get(provider: ProviderId): String? = null

        override fun has(provider: ProviderId): Boolean = false

        override fun clear(provider: ProviderId) = Unit
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun chatRepository(): ChatRepository {
        val retriever =
            RagRetriever(
                object : ChunkVectorSource {
                    override suspend fun chunks(
                        scope: RetrievalScope,
                        limit: Int,
                        offset: Int,
                    ): List<ScopedChunk> = emptyList()
                },
                object : ChunkKeywordSource {
                    override suspend fun match(
                        query: String,
                        scope: RetrievalScope,
                        limit: Int,
                    ): List<Pair<Long, Double>> = emptyList()
                },
            )
        return ChatRepository(
            chatDao = db.chatDao(),
            ragRetriever = retriever,
            providerResolver =
                ProviderResolver(
                    ProviderRegistry(emptyList(), FakeKeyStore()),
                    { null },
                    { false },
                    { false },
                ),
            assembler = ChatContextAssembler(),
            previewBuilder = ChatPreviewBuilder(),
            embedQuery = { AppResult.Success(FloatArray(0)) },
            dispatchers = DefaultDispatcherProvider(),
        )
    }

    private fun viewModel() = ChatHistoryViewModel(chatRepository())

    private suspend fun seedPaper(
        id: String,
        title: String,
    ) {
        val paper =
            Paper(
                ref = ArxivRef(ArxivId(id)),
                latestVersion = 1,
                title = title,
                abstract = "a",
                publishedAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                primaryCategory = "cs.LG",
                categories = listOf("cs.LG"),
                authors = listOf("A"),
                fetchedAt = Instant.EPOCH,
            )
        db.paperDao().upsertPaperWithRelations(paper.toEntity(), paper.authors, paper.categories)
    }

    private suspend fun awaitRows(
        vm: ChatHistoryViewModel,
        predicate: (List<ChatHistoryRow>) -> Boolean,
    ): List<ChatHistoryRow> = withTimeout(5_000) { vm.rows.first { it != null && predicate(it) }!! }

    @Test
    fun `resolves paper titles and collection names, most-recent first`() =
        runBlocking {
            seedPaper("2401.00001", "Attention Is All You Need")
            val collectionId = db.libraryDao().createCollection(CollectionEntity(name = "My KB", createdAt = 0))
            db.chatDao().insertSession(
                ChatSessionEntity(
                    scope = "PAPER",
                    scopeId = "2401.00001",
                    providerId = "CLAUDE",
                    createdAt = 1,
                    lastMessageAt = 1,
                ),
            )
            db.chatDao().insertSession(
                ChatSessionEntity(
                    scope = "COLLECTION",
                    scopeId = collectionId.toString(),
                    providerId = "ON_DEVICE",
                    createdAt = 2,
                    lastMessageAt = 2,
                ),
            )

            val rows = awaitRows(viewModel()) { it.size == 2 }

            // Collection session is newer → first.
            assertTrue(rows.first().isCollection)
            assertEquals("My KB", rows.first().label)
            val paperRow = rows.first { !it.isCollection }
            assertEquals("Attention Is All You Need", paperRow.label)
            assertEquals(RetrievalScope.Paper("2401.00001"), paperRow.scope)
        }

    @Test
    fun `delete removes a session from the list`() =
        runBlocking {
            seedPaper("2401.00001", "Paper One")
            val sid =
                db.chatDao().insertSession(
                    ChatSessionEntity(
                        scope = "PAPER",
                        scopeId = "2401.00001",
                        providerId = "CLAUDE",
                        createdAt = 1,
                        lastMessageAt = 1,
                    ),
                )
            val vm = viewModel()
            awaitRows(vm) { it.size == 1 }

            vm.delete(sid)
            assertTrue(awaitRows(vm) { it.isEmpty() }.isEmpty())
        }

    private suspend fun seedSession(
        scopeId: String,
        at: Long,
    ): Long =
        db.chatDao().insertSession(
            ChatSessionEntity(
                scope = "PAPER",
                scopeId = scopeId,
                providerId = "CLAUDE",
                createdAt = at,
                lastMessageAt = at,
            ),
        )

    @Test
    fun `a custom title wins over the derived label in the row`() =
        runBlocking {
            seedPaper("2401.00001", "Derived Title")
            val id = seedSession("2401.00001", at = 1)
            db.chatDao().renameSession(id, "My custom name")

            val row = awaitRows(viewModel()) { it.size == 1 }.single()
            assertEquals("My custom name", row.customTitle)
            assertEquals("Derived Title", row.label)
            assertEquals("My custom name", row.effectiveTitle())
        }

    @Test
    fun `a null custom title falls back to the derived label, and orphan to null`() =
        runBlocking {
            seedPaper("2401.00001", "Derived Title")
            seedSession("2401.00001", at = 1) // has a paper -> derived label, no custom title
            seedSession("gone.999", at = 2) // orphan: no paper row, no title

            val rows = awaitRows(viewModel()) { it.size == 2 }
            val derived = rows.first { it.scope == RetrievalScope.Paper("2401.00001") }
            val orphan = rows.first { it.scope == RetrievalScope.Paper("gone.999") }
            assertEquals(null, derived.customTitle)
            assertEquals("Derived Title", derived.effectiveTitle())
            assertEquals(null, orphan.effectiveTitle())
        }

    @Test
    fun `pinning floats a session into the Pinned section above a fresher unpinned one`() =
        runBlocking {
            val older = seedSession("p1", at = 1)
            seedSession("p2", at = 99) // fresher, unpinned
            val vm = viewModel()
            awaitRows(vm) { it.size == 2 }

            vm.pin(older, true)

            val rows = awaitRows(vm) { it.first().sessionId == older }
            assertTrue(rows.first().pinned, "the pinned session floats to the top")
            assertEquals(false, rows[1].pinned)
        }

    @Test
    fun `rename through the VM clears back to the derived label on a blank (null) title`() =
        runBlocking {
            seedPaper("2401.00001", "Derived Title")
            val id = seedSession("2401.00001", at = 1)
            val vm = viewModel()
            awaitRows(vm) { it.size == 1 }

            vm.rename(id, "Custom")
            assertEquals("Custom", awaitRows(vm) { it.single().customTitle == "Custom" }.single().customTitle)
            vm.rename(id, null)
            assertEquals(null, awaitRows(vm) { it.single().customTitle == null }.single().customTitle)
        }
}
