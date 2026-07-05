package dev.blokz.arxiver.feature.chat

import androidx.lifecycle.SavedStateHandle
import dev.blokz.arxiver.chat.ChatContextAssembler
import dev.blokz.arxiver.chat.ChatPreviewBuilder
import dev.blokz.arxiver.core.ai.AiKeyStore
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.core.ai.ProviderRegistry
import dev.blokz.arxiver.core.ai.ProviderResolver
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.dao.ChatDao
import dev.blokz.arxiver.core.database.dao.ChatSessionRow
import dev.blokz.arxiver.core.database.entity.ChatMessageEntity
import dev.blokz.arxiver.core.database.entity.ChatSessionEntity
import dev.blokz.arxiver.core.search.ChunkKeywordSource
import dev.blokz.arxiver.core.search.ChunkVectorSource
import dev.blokz.arxiver.core.search.RagRetriever
import dev.blokz.arxiver.core.search.RetrievalScope
import dev.blokz.arxiver.core.search.ScopedChunk
import dev.blokz.arxiver.data.ChatRepository
import dev.blokz.arxiver.feature.paper.ask.SessionStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The route→[SessionStart] resolution (PC.1): `chat/session/{sessionId}` resumes via a DB
 * lookup; `chat/new/{scopeKind}/{scopeId}` forks; anything unparsable resolves [Missing]
 * (a stale back stack pops instead of crashing). Route args arrive as STRINGS — the tests
 * seed the [SavedStateHandle] exactly as Navigation does. Pure JVM: a fake [ChatDao] stands
 * in for Room (only `sessionById` is on this path).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val chatDao = FakeChatDao()

    private class TestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
        override val io: CoroutineDispatcher get() = d
        override val default: CoroutineDispatcher get() = d
        override val main: CoroutineDispatcher get() = d
    }

    private class FakeChatDao : ChatDao {
        private val sessions = MutableStateFlow<List<ChatSessionEntity>>(emptyList())
        private var seq = 0L

        override suspend fun insertSession(session: ChatSessionEntity): Long {
            val id = ++seq
            sessions.value = sessions.value + session.copy(id = id)
            return id
        }

        override suspend fun insertMessage(message: ChatMessageEntity): Long = 0

        override suspend fun updateMessage(
            id: Long,
            content: String,
            status: String,
        ) = Unit

        override suspend fun touchSession(
            id: Long,
            at: Long,
        ) = Unit

        override suspend fun setPinned(
            id: Long,
            pinned: Boolean,
        ) = Unit

        override suspend fun renameSession(
            id: Long,
            title: String?,
        ) = Unit

        override suspend fun messagesFor(sessionId: Long): List<ChatMessageEntity> = emptyList()

        override fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>> = MutableStateFlow(emptyList())

        override fun observeSessions(
            scope: String,
            scopeId: String,
        ): Flow<List<ChatSessionEntity>> =
            sessions.map { list -> list.filter { it.scope == scope && it.scopeId == scopeId } }

        override fun observeSessionRows(): Flow<List<ChatSessionRow>> =
            sessions.map { list -> list.map { ChatSessionRow(it, null, null, null) } }

        override fun observeAllSessions(): Flow<List<ChatSessionEntity>> = sessions

        override suspend fun sessionById(id: Long): ChatSessionEntity? = sessions.value.firstOrNull { it.id == id }

        override suspend fun deleteSession(id: Long) {
            sessions.value = sessions.value.filterNot { it.id == id }
        }
    }

    private object NoKeys : AiKeyStore {
        override fun put(
            provider: ProviderId,
            key: String,
        ) = Unit

        override fun get(provider: ProviderId): String? = null

        override fun has(provider: ProviderId): Boolean = false

        override fun clear(provider: ProviderId) = Unit
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun repo(): ChatRepository =
        ChatRepository(
            chatDao = chatDao,
            ragRetriever =
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
                ),
            providerResolver =
                ProviderResolver(
                    ProviderRegistry(emptyList(), NoKeys),
                    { ProviderId.CLAUDE },
                    { false },
                    { false },
                ),
            assembler = ChatContextAssembler(),
            previewBuilder = ChatPreviewBuilder(),
            embedQuery = { AppResult.Success(FloatArray(384)) },
            dispatchers = TestDispatchers(dispatcher),
        )

    private fun vm(args: Map<String, Any?>) = ChatSessionViewModel(repo(), SavedStateHandle(args))

    private suspend fun seedSession(
        kind: String,
        scopeId: String,
    ): Long =
        chatDao.insertSession(
            ChatSessionEntity(scope = kind, scopeId = scopeId, providerId = "CLAUDE", createdAt = 1, lastMessageAt = 1),
        )

    @Test
    fun `an existing session id resolves Ready-Resume with its reconstructed scope`() =
        runTest(dispatcher) {
            val id = seedSession(ChatSessionEntity.SCOPE_PAPER, "2401.00001")
            // Navigation delivers route args as strings — so does this test.
            val state =
                vm(
                    mapOf("sessionId" to "$id", "title" to "Attention"),
                ).uiState.first { it != ChatSessionUiState.Loading }
            assertEquals(
                ChatSessionUiState.Ready(SessionStart.Resume(RetrievalScope.Paper("2401.00001"), id), "Attention"),
                state,
            )
        }

    @Test
    fun `a deleted session resolves Missing - the stale back stack pops`() =
        runTest(dispatcher) {
            val state = vm(mapOf("sessionId" to "999")).uiState.first { it != ChatSessionUiState.Loading }
            assertEquals(ChatSessionUiState.Missing, state)
        }

    @Test
    fun `the new-conversation route resolves Ready-New for both scope kinds`() =
        runTest(dispatcher) {
            val paper =
                vm(mapOf("scopeKind" to CHAT_SCOPE_KIND_PAPER, "scopeId" to "math/0211159"))
                    .uiState.first { it != ChatSessionUiState.Loading }
            assertEquals(
                ChatSessionUiState.Ready(SessionStart.New(RetrievalScope.Paper("math/0211159")), null),
                paper,
            )

            val collection =
                vm(mapOf("scopeKind" to CHAT_SCOPE_KIND_COLLECTION, "scopeId" to "7", "title" to "Reading list"))
                    .uiState.first { it != ChatSessionUiState.Loading }
            assertEquals(
                ChatSessionUiState.Ready(SessionStart.New(RetrievalScope.Collection(7)), "Reading list"),
                collection,
            )
        }

    @Test
    fun `unparsable args resolve Missing - never a crash`() =
        runTest(dispatcher) {
            // Non-numeric collection id.
            assertEquals(
                ChatSessionUiState.Missing,
                vm(mapOf("scopeKind" to CHAT_SCOPE_KIND_COLLECTION, "scopeId" to "not-a-number"))
                    .uiState.first { it != ChatSessionUiState.Loading },
            )
            // Unknown scope kind.
            assertEquals(
                ChatSessionUiState.Missing,
                vm(mapOf("scopeKind" to "gopher", "scopeId" to "7"))
                    .uiState.first { it != ChatSessionUiState.Loading },
            )
            // No args at all.
            assertEquals(
                ChatSessionUiState.Missing,
                vm(emptyMap()).uiState.first { it != ChatSessionUiState.Loading },
            )
        }

    @Test
    fun `a blank title - the builders' no-title encoding - resolves to null`() =
        runTest(dispatcher) {
            val id = seedSession(ChatSessionEntity.SCOPE_PAPER, "2401.00001")
            val state =
                vm(mapOf("sessionId" to "$id", "title" to ""))
                    .uiState.first { it != ChatSessionUiState.Loading }
            assertEquals(
                ChatSessionUiState.Ready(SessionStart.Resume(RetrievalScope.Paper("2401.00001"), id), null),
                state,
            )
        }

    @Test
    fun `a collection session id resolves the collection scope`() =
        runTest(dispatcher) {
            val id = seedSession(ChatSessionEntity.SCOPE_COLLECTION, "7")
            val state = vm(mapOf("sessionId" to "$id")).uiState.first { it != ChatSessionUiState.Loading }
            assertEquals(
                ChatSessionUiState.Ready(SessionStart.Resume(RetrievalScope.Collection(7), id), null),
                state,
            )
        }

    @Test
    fun `a session whose collection id no longer parses resolves Missing`() =
        runTest(dispatcher) {
            val id = seedSession(ChatSessionEntity.SCOPE_COLLECTION, "corrupt")
            val state = vm(mapOf("sessionId" to "$id")).uiState.first { it != ChatSessionUiState.Loading }
            assertEquals(ChatSessionUiState.Missing, state)
        }
}
