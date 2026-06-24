package dev.blokz.arxiver.feature.paper.ask

import dev.blokz.arxiver.chat.ChatContextAssembler
import dev.blokz.arxiver.chat.ChatPreviewBuilder
import dev.blokz.arxiver.core.ai.AiException
import dev.blokz.arxiver.core.ai.AiKeyStore
import dev.blokz.arxiver.core.ai.AiProvider
import dev.blokz.arxiver.core.ai.ChatChunk
import dev.blokz.arxiver.core.ai.ChatRequest
import dev.blokz.arxiver.core.ai.ProviderCapability
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.core.ai.ProviderRegistry
import dev.blokz.arxiver.core.ai.ProviderResolver
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.dao.ChatDao
import dev.blokz.arxiver.core.database.entity.ChatMessageEntity
import dev.blokz.arxiver.core.database.entity.ChatSessionEntity
import dev.blokz.arxiver.core.search.ChunkKeywordSource
import dev.blokz.arxiver.core.search.ChunkVectorSource
import dev.blokz.arxiver.core.search.RagRetriever
import dev.blokz.arxiver.core.search.RetrievalScope
import dev.blokz.arxiver.core.search.ScopedChunk
import dev.blokz.arxiver.data.ChatRepository
import dev.blokz.arxiver.rag.ScopeIndexer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fully-deterministic VM test: a fake [ChatDao] (no Room) and a single
 * [StandardTestDispatcher] for the VM + repository, driven by `advanceUntilIdle`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val chatDao = FakeChatDao()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class TestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
        override val io = d
        override val default = d
        override val main = d
    }

    private class FakeProvider(
        override val id: ProviderId,
        requiresKey: Boolean,
        private val script: () -> Flow<ChatChunk>,
    ) : AiProvider {
        override val capability =
            ProviderCapability(100_000, streaming = true, onDevice = !requiresKey, requiresKey = requiresKey)

        override fun chat(request: ChatRequest): Flow<ChatChunk> = script()
    }

    private class FakeKeyStore(private val keys: Set<ProviderId>) : AiKeyStore {
        override fun put(
            provider: ProviderId,
            key: String,
        ) = Unit

        override fun get(provider: ProviderId): String? = if (provider in keys) "k" else null

        override fun has(provider: ProviderId): Boolean = provider in keys

        override fun clear(provider: ProviderId) = Unit
    }

    /** In-memory [ChatDao] — keeps the test free of Room and real IO. */
    private class FakeChatDao : ChatDao {
        private val sessions = MutableStateFlow<List<ChatSessionEntity>>(emptyList())
        private val messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
        private var sessionSeq = 0L
        private var messageSeq = 0L

        override suspend fun insertSession(session: ChatSessionEntity): Long {
            val id = ++sessionSeq
            sessions.value = sessions.value + session.copy(id = id)
            return id
        }

        override suspend fun insertMessage(message: ChatMessageEntity): Long {
            val id = ++messageSeq
            messages.value = messages.value + message.copy(id = id)
            return id
        }

        override suspend fun updateMessage(
            id: Long,
            content: String,
            status: String,
        ) {
            messages.value = messages.value.map { if (it.id == id) it.copy(content = content, status = status) else it }
        }

        override suspend fun touchSession(
            id: Long,
            at: Long,
        ) {
            sessions.value = sessions.value.map { if (it.id == id) it.copy(lastMessageAt = at) else it }
        }

        override suspend fun messagesFor(sessionId: Long): List<ChatMessageEntity> =
            messages.value.filter { it.sessionId == sessionId }.sortedWith(compareBy({ it.createdAt }, { it.id }))

        override fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>> =
            messages.map {
                    list ->
                list.filter { it.sessionId == sessionId }.sortedWith(compareBy({ it.createdAt }, { it.id }))
            }

        override fun observeSessions(
            scope: String,
            scopeId: String,
        ): Flow<List<ChatSessionEntity>> =
            sessions.map {
                    list ->
                list.filter { it.scope == scope && it.scopeId == scopeId }.sortedByDescending { it.lastMessageAt }
            }

        override fun observeAllSessions(): Flow<List<ChatSessionEntity>> =
            sessions.map { list -> list.sortedByDescending { it.lastMessageAt } }

        override suspend fun sessionById(id: Long): ChatSessionEntity? = sessions.value.firstOrNull { it.id == id }

        override suspend fun deleteSession(id: Long) {
            sessions.value = sessions.value.filterNot { it.id == id }
            messages.value = messages.value.filterNot { it.sessionId == id }
        }
    }

    private val emptyRetriever =
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

    private fun vm(
        provider: AiProvider,
        selected: ProviderId,
        keys: Set<ProviderId> = emptySet(),
        onDeviceReady: Boolean = false,
        embed: suspend (String) -> AppResult<FloatArray> = { AppResult.Success(FloatArray(384)) },
        indexer: ScopeIndexer = ScopeIndexer {},
        scope: RetrievalScope = RetrievalScope.Paper("2401.00001"),
    ): AskViewModel {
        val registry = ProviderRegistry(listOf(provider), FakeKeyStore(keys))
        val resolver = ProviderResolver(registry, { selected }, { false }, { onDeviceReady })
        val repo =
            ChatRepository(
                chatDao = chatDao,
                ragRetriever = emptyRetriever,
                providerResolver = resolver,
                assembler = ChatContextAssembler(),
                previewBuilder = ChatPreviewBuilder(),
                embedQuery = embed,
                dispatchers = TestDispatchers(dispatcher),
            )
        return AskViewModel(repo, indexer).also { it.start(scope) }
    }

    private fun onDeviceProvider(script: () -> Flow<ChatChunk>) =
        FakeProvider(ProviderId.ON_DEVICE, requiresKey = false, script = script)

    private fun cloudProvider(script: () -> Flow<ChatChunk>) =
        FakeProvider(ProviderId.CLAUDE, requiresKey = true, script = script)

    @Test
    fun `on-device question streams immediately without a confirm`() =
        runTest(dispatcher) {
            val vm =
                vm(
                    onDeviceProvider { flowOf(ChatChunk.Delta("Hello "), ChatChunk.Delta("world"), ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                )
            vm.setInput("What is this?")
            vm.send()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(false, state.isCloud)
            assertNull(state.pendingConfirm)
            assertEquals(2, state.messages.size)
            assertEquals(AskRole.USER, state.messages.first().role)
            assertEquals("Hello world", state.messages.last().text)
            assertEquals(false, state.messages.last().streaming)
        }

    @Test
    fun `a cloud question pauses on the confirm and only streams after confirmSend`() =
        runTest(dispatcher) {
            val vm =
                vm(
                    cloudProvider { flowOf(ChatChunk.Delta("Answer"), ChatChunk.Done()) },
                    selected = ProviderId.CLAUDE,
                    keys = setOf(ProviderId.CLAUDE),
                )
            vm.setInput("Why?")
            vm.send()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.isCloud)
            assertTrue(vm.uiState.value.pendingConfirm != null)
            assertTrue(vm.uiState.value.messages.isEmpty(), "no turn shown until confirmed")

            vm.confirmSend()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertNull(state.pendingConfirm)
            assertEquals(2, state.messages.size)
            assertEquals("Answer", state.messages.last().text)
        }

    @Test
    fun `a question waits for scope indexing before preparing`() =
        runTest(dispatcher) {
            // An un-embedded inbox paper is indexed on open; retrieval must not race ahead
            // of that indexing or it would send no context (regression: per-paper Ask was
            // ungrounded for inbox papers because the Paper scope indexer was a no-op).
            val gate = CompletableDeferred<Unit>()
            val vm =
                vm(
                    cloudProvider { flowOf(ChatChunk.Delta("Answer"), ChatChunk.Done()) },
                    selected = ProviderId.CLAUDE,
                    keys = setOf(ProviderId.CLAUDE),
                    indexer = ScopeIndexer { gate.await() },
                )
            vm.setInput("Why?")
            vm.send()
            advanceUntilIdle()

            // Indexing is still in flight, so prepare has not produced a confirm yet.
            assertNull(vm.uiState.value.pendingConfirm)
            assertTrue(vm.uiState.value.preparing)

            gate.complete(Unit)
            advanceUntilIdle()

            // Indexing done → prepare ran → the confirm is shown.
            assertTrue(vm.uiState.value.pendingConfirm != null)
        }

    @Test
    fun `no usable provider surfaces notConfigured and adds no turn`() =
        runTest(dispatcher) {
            val vm = vm(cloudProvider { flowOf(ChatChunk.Done()) }, selected = ProviderId.CLAUDE, keys = emptySet())
            vm.setInput("hi")
            vm.send()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.notConfigured)
            assertTrue(vm.uiState.value.messages.isEmpty())
        }

    @Test
    fun `a stream error marks the turn and surfaces an error`() =
        runTest(dispatcher) {
            val vm =
                vm(
                    onDeviceProvider {
                        flow {
                            emit(ChatChunk.Delta("partial"))
                            throw AiException(AppError.Offline)
                        }
                    },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                )
            vm.setInput("q")
            vm.send()
            advanceUntilIdle()

            // The turn is marked failed and an error surfaces. (Durable partial-text persistence
            // is guaranteed by the repo's finalize and covered in ChatRepositoryTest; the live
            // bubble may drop a same-tick delta that flowOn discards on cancellation.)
            val state = vm.uiState.value
            assertTrue(state.error != null)
            assertEquals(AskRole.ASSISTANT, state.messages.last().role)
            assertTrue(state.messages.last().error)
            assertEquals(false, state.streaming)
        }

    @Test
    fun `a provider that completes with no tokens surfaces an error, not a blank answer`() =
        runTest(dispatcher) {
            // Provider completes with zero deltas (the on-device-empty case).
            val vm =
                vm(
                    onDeviceProvider { flowOf(ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                )
            vm.setInput("q")
            vm.send()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state.error != null, "empty completion must surface an error")
            assertTrue(state.messages.last().error)
            assertEquals("", state.messages.last().text)
            assertEquals(false, state.streaming)
        }

    @Test
    fun `a collection scope ensures the collection is indexed on open`() =
        runTest(dispatcher) {
            val indexed = mutableListOf<RetrievalScope>()
            vm(
                onDeviceProvider { flowOf(ChatChunk.Done()) },
                selected = ProviderId.ON_DEVICE,
                onDeviceReady = true,
                indexer = ScopeIndexer { indexed += it },
                scope = RetrievalScope.Collection(7),
            )
            advanceUntilIdle()

            assertEquals(listOf<RetrievalScope>(RetrievalScope.Collection(7)), indexed)
        }

    @Test
    fun `summarize sends the canned prompt as the user turn`() =
        runTest(dispatcher) {
            val vm =
                vm(
                    onDeviceProvider { flowOf(ChatChunk.Delta("Summary."), ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                )
            vm.summarize()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(2, state.messages.size)
            assertEquals(AskViewModel.SUMMARIZE_PROMPT, state.messages.first().text)
            assertEquals("Summary.", state.messages.last().text)
        }
}
