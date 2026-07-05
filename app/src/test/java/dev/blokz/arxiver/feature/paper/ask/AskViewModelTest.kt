package dev.blokz.arxiver.feature.paper.ask

import androidx.lifecycle.SavedStateHandle
import dev.blokz.arxiver.R
import dev.blokz.arxiver.chat.ChatContextAssembler
import dev.blokz.arxiver.chat.ChatPreviewBuilder
import dev.blokz.arxiver.core.ai.AiException
import dev.blokz.arxiver.core.ai.AiKeyStore
import dev.blokz.arxiver.core.ai.AiProvider
import dev.blokz.arxiver.core.ai.ChatChunk
import dev.blokz.arxiver.core.ai.ChatImage
import dev.blokz.arxiver.core.ai.ChatRequest
import dev.blokz.arxiver.core.ai.OutputRichness
import dev.blokz.arxiver.core.ai.ProviderCapability
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.core.ai.ProviderRegistry
import dev.blokz.arxiver.core.ai.ProviderResolver
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.dao.ChatDao
import dev.blokz.arxiver.core.database.dao.ChatSessionRow
import dev.blokz.arxiver.core.database.entity.ChatMessageEntity
import dev.blokz.arxiver.core.database.entity.ChatSessionEntity
import dev.blokz.arxiver.core.search.ChunkKeywordSource
import dev.blokz.arxiver.core.search.ChunkVectorSource
import dev.blokz.arxiver.core.search.RagRetriever
import dev.blokz.arxiver.core.search.RelationEdge
import dev.blokz.arxiver.core.search.RelationEdgeKind
import dev.blokz.arxiver.core.search.RelationGraph
import dev.blokz.arxiver.core.search.RelationNode
import dev.blokz.arxiver.core.search.RetrievalScope
import dev.blokz.arxiver.core.search.ScopedChunk
import dev.blokz.arxiver.data.ChatRepository
import dev.blokz.arxiver.data.GraphResult
import dev.blokz.arxiver.data.PageImageSource
import dev.blokz.arxiver.data.RelationGraphSource
import dev.blokz.arxiver.rag.ScopeIndexer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertNotNull
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
        vision: Boolean = false,
        private val script: () -> Flow<ChatChunk>,
    ) : AiProvider {
        override val capability =
            ProviderCapability(
                100_000,
                streaming = true,
                onDevice = !requiresKey,
                requiresKey = requiresKey,
                richness = if (requiresKey) OutputRichness.FULL else OutputRichness.PLAIN,
                vision = vision,
            )

        override fun chat(request: ChatRequest): Flow<ChatChunk> = script()
    }

    /**
     * Configurable [PageImageSource] fake (R3d.4). [pageCount] drives sheet-open gating; [image]
     * is what [pageImage] returns (null = render failure). Records the requested **0-based** index
     * so a test can assert the single 1→0 conversion (m1).
     */
    private class FakePageImageSource(
        private val pageCount: Int? = null,
        private val image: ChatImage? = ChatImage("image/jpeg", "QUFB", label = "page 1 of arXiv:2401.00001"),
    ) : PageImageSource {
        var requestedPageIndex: Int? = null
            private set
        var pageCountQueries: Int = 0
            private set

        override suspend fun pageCountIfLocal(paperId: String): Int? {
            pageCountQueries++
            return pageCount
        }

        override suspend fun pageImage(
            paperId: String,
            pageIndex: Int,
        ): ChatImage? {
            requestedPageIndex = pageIndex
            return image
        }
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

        override suspend fun setPinned(
            id: Long,
            pinned: Boolean,
        ) = Unit

        override suspend fun renameSession(
            id: Long,
            title: String?,
        ) = Unit

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

        override fun observeSessionRows(): Flow<List<ChatSessionRow>> =
            sessions.map { list -> list.map { ChatSessionRow(it, null, null, null) } }

        override fun observeAllSessions(): Flow<List<ChatSessionEntity>> =
            sessions.map { list -> list.sortedByDescending { it.lastMessageAt } }

        override fun observeSession(id: Long): Flow<ChatSessionEntity?> =
            sessions.map { list -> list.firstOrNull { it.id == id } }

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
        pageImageSource: PageImageSource = FakePageImageSource(),
        relationGraphSource: RelationGraphSource = RelationGraphSource { GraphResult.NoRelations },
        sessionStart: SessionStart? = null,
        resumeSessionId: Long? = null,
        savedState: SavedStateHandle = SavedStateHandle(),
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
        return AskViewModel(
            repo,
            indexer,
            pageImageSource,
            relationGraphSource,
            NoopReadAloud(),
            savedState,
        ).also {
            // sessionStart exercises the sealed API directly; resumeSessionId exercises the
            // delegating sheet overload's Resume mapping; default = the sheet's MostRecentFor.
            if (sessionStart != null) it.start(sessionStart) else it.start(scope, resumeSessionId)
        }
    }

    private class NoopReadAloud : dev.blokz.arxiver.tts.ReadAloud {
        override val speakingId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

        override fun toggle(
            id: String,
            text: String,
        ) = Unit

        override fun stop() = Unit
    }

    private fun onDeviceProvider(script: () -> Flow<ChatChunk>) =
        FakeProvider(ProviderId.ON_DEVICE, requiresKey = false, script = script)

    private fun cloudProvider(script: () -> Flow<ChatChunk>) =
        FakeProvider(ProviderId.CLAUDE, requiresKey = true, script = script)

    private fun cloudVisionProvider(script: () -> Flow<ChatChunk>) =
        FakeProvider(ProviderId.CLAUDE, requiresKey = true, vision = true, script = script)

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

    // --- R3d.4 vision preset ---

    @Test
    fun `start gates visionAvailable on provider vision AND a local PDF`() =
        runTest(dispatcher) {
            // vision provider + a local PDF → available.
            val withBoth =
                vm(
                    cloudVisionProvider { flowOf(ChatChunk.Done()) },
                    selected = ProviderId.CLAUDE,
                    keys = setOf(ProviderId.CLAUDE),
                    pageImageSource = FakePageImageSource(pageCount = 3),
                )
            advanceUntilIdle()
            assertTrue(withBoth.uiState.value.visionAvailable)
            assertEquals(3, withBoth.uiState.value.pageCount)

            // vision provider but NO local PDF → unavailable.
            val noPdf =
                vm(
                    cloudVisionProvider { flowOf(ChatChunk.Done()) },
                    selected = ProviderId.CLAUDE,
                    keys = setOf(ProviderId.CLAUDE),
                    pageImageSource = FakePageImageSource(pageCount = null),
                )
            advanceUntilIdle()
            assertEquals(false, noPdf.uiState.value.visionAvailable)
            assertEquals(0, noPdf.uiState.value.pageCount)

            // non-vision (on-device) provider + a local PDF → unavailable (provider gate).
            val noVision =
                vm(
                    onDeviceProvider { flowOf(ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                    pageImageSource = FakePageImageSource(pageCount = 3),
                )
            advanceUntilIdle()
            assertEquals(false, noVision.uiState.value.visionAvailable)
        }

    @Test
    fun `collection scope skips vision gating and runVisionPreset is a no-op`() =
        runTest(dispatcher) {
            val source = FakePageImageSource(pageCount = 5)
            val vm =
                vm(
                    cloudVisionProvider { flowOf(ChatChunk.Delta("x"), ChatChunk.Done()) },
                    selected = ProviderId.CLAUDE,
                    keys = setOf(ProviderId.CLAUDE),
                    scope = RetrievalScope.Collection(7),
                    pageImageSource = source,
                )
            advanceUntilIdle()
            // Gating never probes for a collection scope.
            assertEquals(false, vm.uiState.value.visionAvailable)
            assertEquals(0, vm.uiState.value.pageCount)
            assertEquals(0, source.pageCountQueries)

            // m2: runVisionPreset is a no-op for a collection (no single target paper).
            vm.runVisionPreset("instruction", 1)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.messages.isEmpty())
            assertNull(vm.uiState.value.pendingConfirm)
            assertEquals(false, vm.uiState.value.preparing)
            assertNull(source.requestedPageIndex)
        }

    @Test
    fun `runVisionPreset converts the 1-based page to 0-based exactly once`() =
        runTest(dispatcher) {
            // m1: picker page 2 must request page index 1 (the only conversion is at the VM boundary).
            val source = FakePageImageSource(pageCount = 5)
            val vm =
                vm(
                    cloudVisionProvider { flowOf(ChatChunk.Delta("x"), ChatChunk.Done()) },
                    selected = ProviderId.CLAUDE,
                    keys = setOf(ProviderId.CLAUDE),
                    pageImageSource = source,
                )
            vm.runVisionPreset("instruction", 2)
            advanceUntilIdle()
            assertEquals(1, source.requestedPageIndex)
        }

    @Test
    fun `runVisionPreset attaches the page image and parks on the cloud confirm`() =
        runTest(dispatcher) {
            val vm =
                vm(
                    cloudVisionProvider { flowOf(ChatChunk.Delta("Figure answer"), ChatChunk.Done()) },
                    selected = ProviderId.CLAUDE,
                    keys = setOf(ProviderId.CLAUDE),
                    pageImageSource = FakePageImageSource(pageCount = 4),
                )
            vm.runVisionPreset("Summarize with figures", 1)
            advanceUntilIdle()

            // Cloud turn pauses on the confirm; nothing sent yet.
            val parked = vm.uiState.value
            assertTrue(parked.isCloud)
            assertNotNull(parked.pendingConfirm)
            assertTrue(parked.messages.isEmpty(), "no turn shown until confirmed")
            // SB2: the attachment really reached prepare() — the preview discloses the image
            // (a plain text turn would NOT contain this), tying the VM path to the real attachment.
            assertTrue(
                parked.pendingConfirm!!.text.contains("Attached image"),
                "the privacy preview must disclose the attached page image",
            )

            vm.confirmSend()
            advanceUntilIdle()
            val state = vm.uiState.value
            assertNull(state.pendingConfirm)
            assertEquals(2, state.messages.size)
            assertEquals("Figure answer", state.messages.last().text)
        }

    @Test
    fun `runVisionPreset surfaces an error when the page image cannot be read`() =
        runTest(dispatcher) {
            val source = FakePageImageSource(pageCount = 3, image = null)
            val vm =
                vm(
                    cloudVisionProvider { flowOf(ChatChunk.Done()) },
                    selected = ProviderId.CLAUDE,
                    keys = setOf(ProviderId.CLAUDE),
                    pageImageSource = source,
                )
            vm.runVisionPreset("instruction", 1)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(R.string.ask_error_page_image, state.error)
            assertEquals(false, state.preparing)
            assertTrue(state.messages.isEmpty())
            assertNull(state.pendingConfirm)
            assertEquals(0, source.requestedPageIndex) // pageImage WAS reached (vs. the no-vision path)
        }

    @Test
    fun `runVisionPreset re-checks vision at action time and errors if the provider lost it`() =
        runTest(dispatcher) {
            // SB1: the sheet-open gate can go stale (e.g. an on-device engine becomes ready mid-sheet).
            // A non-vision resolved provider must surface an error + hide the chip, not silently drop
            // the image and run a figure-less text turn.
            val source = FakePageImageSource(pageCount = 3)
            val vm =
                vm(
                    onDeviceProvider { flowOf(ChatChunk.Delta("x"), ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                    pageImageSource = source,
                )
            vm.runVisionPreset("instruction", 1)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(R.string.ask_error_no_vision, state.error)
            assertEquals(false, state.visionAvailable)
            assertEquals(false, state.preparing)
            assertTrue(state.messages.isEmpty())
            assertNull(source.requestedPageIndex) // the re-check returns BEFORE rendering the page
        }

    // --- P-Atlas PA.1 app-drawn relation graph ---

    @Test
    fun `runGraphArtifact draws an app-composed graph turn with no provider call`() =
        runTest(dispatcher) {
            val graph =
                RelationGraph(
                    nodes =
                        listOf(
                            RelationNode("2401.00001", "Center", isCenter = true),
                            RelationNode("2402.00002", "Neighbor", inLibrary = true),
                        ),
                    edges = listOf(RelationEdge("2401.00001", "2402.00002", RelationEdgeKind.SIMILAR, 0.8)),
                )
            val vm =
                vm(
                    // The provider must NOT be touched — an artifact never calls prepare()/stream().
                    cloudProvider { error("provider must not be called for an app-drawn artifact") },
                    selected = ProviderId.CLAUDE,
                    keys = setOf(ProviderId.CLAUDE),
                    relationGraphSource = RelationGraphSource { GraphResult.Ready(graph) },
                )
            vm.runGraphArtifact("Map relationships")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertNull(state.pendingConfirm, "no privacy confirm — nothing leaves the device")
            assertEquals(false, state.preparing)
            assertEquals(2, state.messages.size)
            assertEquals("Map relationships", state.messages.first().text)
            val answer = state.messages.last()
            assertEquals(AskRole.ASSISTANT, answer.role)
            assertEquals(false, answer.error)
            assertTrue(answer.text.contains("```mermaid"), "the assistant turn carries the app-drawn Mermaid")
        }

    @Test
    fun `runGraphArtifact surfaces the not-embedded empty state`() =
        runTest(dispatcher) {
            val vm =
                vm(
                    cloudProvider { error("provider must not be called") },
                    selected = ProviderId.CLAUDE,
                    keys = setOf(ProviderId.CLAUDE),
                    relationGraphSource = RelationGraphSource { GraphResult.NotEmbedded },
                )
            vm.runGraphArtifact("Map relationships")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(R.string.ask_graph_not_embedded, state.error)
            assertEquals(false, state.preparing)
            assertTrue(state.messages.isEmpty())
        }

    // --- PH.7: offerQuote consume-once-by-id (the reader selection prefill seam) ---

    @Test
    fun `offerQuote applies once per id and never re-applies over user edits`() =
        runTest(dispatcher) {
            val vm =
                vm(
                    onDeviceProvider { kotlinx.coroutines.flow.flowOf(ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                )

            vm.offerQuote(AskQuoteRequest(id = 1L, text = "the attention mechanism"))
            assertTrue(vm.uiState.value.input.startsWith("> the attention mechanism"))

            vm.setInput("my edited question")
            vm.offerQuote(AskQuoteRequest(id = 1L, text = "the attention mechanism"))
            assertEquals("my edited question", vm.uiState.value.input, "same id never re-applies")
        }

    @Test
    fun `a new id applies even for identical text, prepending onto the current input`() =
        runTest(dispatcher) {
            val vm =
                vm(
                    onDeviceProvider { kotlinx.coroutines.flow.flowOf(ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                )

            vm.offerQuote(AskQuoteRequest(id = 1L, text = "same text"))
            vm.setInput("draft")
            vm.offerQuote(AskQuoteRequest(id = 2L, text = "same text"))

            assertEquals("> same text\n\ndraft", vm.uiState.value.input)
        }

    // --- P-Chat PC.0: cross-surface write-redirect fix + ghost-bubble hydrate filter ---

    private suspend fun seedSession(lastMessageAt: Long): Long =
        chatDao.insertSession(
            ChatSessionEntity(
                scope = ChatSessionEntity.SCOPE_PAPER,
                scopeId = "2401.00001",
                providerId = "CLAUDE",
                createdAt = lastMessageAt,
                lastMessageAt = lastMessageAt,
            ),
        )

    @Test
    fun `a bound session never re-syncs to the scope's newest session after a turn`() =
        runTest(dispatcher) {
            // The factory's start() resumes the scope's most-recent session — A, the only one.
            val sessionA = seedSession(lastMessageAt = 1_000)
            val vm =
                vm(
                    onDeviceProvider { flowOf(ChatChunk.Delta("answer"), ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                )
            advanceUntilIdle()

            // Another surface (the P-Chat full-screen route) makes a NEWER session on the same
            // scope. MAX_VALUE so it stays newest even after send #1 touches A with wall-clock time.
            val sessionB = seedSession(lastMessageAt = Long.MAX_VALUE)

            // Send #1: lands in A; the pre-fix finally block would now re-sync the binding to B.
            vm.setInput("first question")
            vm.send()
            advanceUntilIdle()
            // Send #2: the regression bite — under the bug this lands in B.
            vm.setInput("second question")
            vm.send()
            advanceUntilIdle()

            val turnsInA = chatDao.messagesFor(sessionA).filter { it.role == ChatMessageEntity.ROLE_USER }
            assertEquals(
                listOf("first question", "second question"),
                turnsInA.map { it.content },
                "every turn stays in the session THIS surface displays, never the scope's newest",
            )
            assertTrue(chatDao.messagesFor(sessionB).isEmpty(), "the other surface's session is untouched")
        }

    @Test
    fun `a first turn adopts the session stream lazily created - and keeps it`() =
        runTest(dispatcher) {
            val vm =
                vm(
                    onDeviceProvider { flowOf(ChatChunk.Delta("a"), ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                )
            advanceUntilIdle()

            vm.setInput("first")
            vm.send()
            advanceUntilIdle()
            vm.setInput("second")
            vm.send()
            advanceUntilIdle()

            val sessions = chatDao.observeAllSessions().first()
            assertEquals(1, sessions.size, "both turns share the one lazily-created session")
            assertEquals(4, chatDao.messagesFor(sessions.single().id).size)
        }

    @Test
    fun `hydrate drops exactly the empty-incomplete assistant ghost`() =
        runTest(dispatcher) {
            val session = seedSession(lastMessageAt = 1_000)

            suspend fun msg(
                role: String,
                content: String,
                status: String,
                at: Long,
            ) = chatDao.insertMessage(
                ChatMessageEntity(
                    sessionId = session,
                    role = role,
                    content = content,
                    status = status,
                    createdAt = at,
                ),
            )
            msg(ChatMessageEntity.ROLE_USER, "question", ChatMessageEntity.STATUS_COMPLETE, 1)
            msg(ChatMessageEntity.ROLE_ASSISTANT, "", ChatMessageEntity.STATUS_INCOMPLETE, 2) // the ghost
            msg(ChatMessageEntity.ROLE_ASSISTANT, "partial answer…", ChatMessageEntity.STATUS_INCOMPLETE, 3)
            msg(ChatMessageEntity.ROLE_ASSISTANT, "", ChatMessageEntity.STATUS_ERROR, 4)

            val vm =
                vm(
                    onDeviceProvider { flowOf(ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                )
            advanceUntilIdle()

            val texts = vm.uiState.value.messages.map { it.text }
            assertEquals(listOf("question", "partial answer…", ""), texts, "only the empty-incomplete row dropped")
        }

    @Test
    fun `a first turn binds the exact session it creates - not the scope's newest`() =
        runTest(dispatcher) {
            val vm =
                vm(
                    onDeviceProvider { flowOf(ChatChunk.Delta("a"), ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                )
            advanceUntilIdle()

            // Another surface creates a NEWER session on the same scope before this surface's
            // first turn — the deleted most-recent "learn" heuristic would adopt it post-turn.
            val foreign = seedSession(lastMessageAt = Long.MAX_VALUE)

            vm.setInput("first")
            vm.send()
            advanceUntilIdle()
            vm.setInput("second")
            vm.send()
            advanceUntilIdle()

            val own = chatDao.observeAllSessions().first().map { it.id }.single { it != foreign }
            assertEquals(4, chatDao.messagesFor(own).size, "both turns in the session this surface created")
            assertTrue(chatDao.messagesFor(foreign).isEmpty(), "the foreign session is never written")
        }

    @Test
    fun `a send issued before hydration completes still lands in the resumed session`() =
        runTest(dispatcher) {
            val sessionA = seedSession(lastMessageAt = 1_000)
            val vm =
                vm(
                    onDeviceProvider { flowOf(ChatChunk.Delta("a"), ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                )
            // Deliberately NO advanceUntilIdle: hydration is still pending at send time.
            vm.setInput("early question")
            vm.send()
            advanceUntilIdle()

            assertEquals(1, chatDao.observeAllSessions().first().size, "a racing send never forks a session")
            assertTrue(chatDao.messagesFor(sessionA).any { it.content == "early question" })
        }

    // --- P-Chat PC.1: sealed SessionStart + SavedStateHandle binding ---

    private suspend fun seedMessage(
        sessionId: Long,
        content: String,
        at: Long,
    ) = chatDao.insertMessage(
        ChatMessageEntity(
            sessionId = sessionId,
            role = ChatMessageEntity.ROLE_USER,
            content = content,
            status = ChatMessageEntity.STATUS_COMPLETE,
            createdAt = at,
        ),
    )

    @Test
    fun `start New ignores existing sessions and forks on first send`() =
        runTest(dispatcher) {
            val existing = seedSession(lastMessageAt = 1_000)
            seedMessage(existing, "old turn", at = 1)
            val vm =
                vm(
                    onDeviceProvider { flowOf(ChatChunk.Delta("a"), ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                    sessionStart = SessionStart.New(RetrievalScope.Paper("2401.00001")),
                )
            advanceUntilIdle()
            assertTrue(vm.uiState.value.messages.isEmpty(), "New never hydrates the scope's history")

            vm.setInput("fresh question")
            vm.send()
            advanceUntilIdle()

            assertEquals(2, chatDao.observeAllSessions().first().size, "the first send forked a session")
            assertTrue(chatDao.messagesFor(existing).none { it.content == "fresh question" })
        }

    @Test
    fun `start Resume hydrates exactly the given session - not the scope's newest`() =
        runTest(dispatcher) {
            val older = seedSession(lastMessageAt = 1_000)
            val newer = seedSession(lastMessageAt = 2_000)
            seedMessage(older, "older turn", at = 1)
            seedMessage(newer, "newer turn", at = 2)
            val vm =
                vm(
                    onDeviceProvider { flowOf(ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                    sessionStart = SessionStart.Resume(RetrievalScope.Paper("2401.00001"), older),
                )
            advanceUntilIdle()
            assertEquals(listOf("older turn"), vm.uiState.value.messages.map { it.text })
        }

    @Test
    fun `start MostRecentFor resumes the scope's latest session`() =
        runTest(dispatcher) {
            val older = seedSession(lastMessageAt = 1_000)
            val newer = seedSession(lastMessageAt = 2_000)
            seedMessage(older, "older turn", at = 1)
            seedMessage(newer, "newer turn", at = 2)
            val vm =
                vm(
                    onDeviceProvider { flowOf(ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                    sessionStart = SessionStart.MostRecentFor(RetrievalScope.Paper("2401.00001")),
                )
            advanceUntilIdle()
            assertEquals(listOf("newer turn"), vm.uiState.value.messages.map { it.text })
        }

    @Test
    fun `the delegating sheet overload maps a session id to Resume`() =
        runTest(dispatcher) {
            val older = seedSession(lastMessageAt = 1_000)
            val newer = seedSession(lastMessageAt = 2_000)
            seedMessage(older, "older turn", at = 1)
            seedMessage(newer, "newer turn", at = 2)
            val vm =
                vm(
                    onDeviceProvider { flowOf(ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                    resumeSessionId = older,
                )
            advanceUntilIdle()
            assertEquals(listOf("older turn"), vm.uiState.value.messages.map { it.text })
        }

    @Test
    fun `a New route restored after process death re-binds its created session`() =
        runTest(dispatcher) {
            val bound = seedSession(lastMessageAt = 1_000)
            seedMessage(bound, "the forked conversation", at = 1)
            seedSession(lastMessageAt = 9_999) // a newer session that must NOT win the binding
            val vm =
                vm(
                    onDeviceProvider { flowOf(ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                    sessionStart = SessionStart.New(RetrievalScope.Paper("2401.00001")),
                    savedState = SavedStateHandle(mapOf(AskViewModel.KEY_BOUND_SESSION_ID to bound)),
                )
            advanceUntilIdle()
            assertEquals(listOf("the forked conversation"), vm.uiState.value.messages.map { it.text })
        }

    @Test
    fun `the first send from New persists the created session id for restore`() =
        runTest(dispatcher) {
            val savedState = SavedStateHandle()
            val vm =
                vm(
                    onDeviceProvider { flowOf(ChatChunk.Delta("a"), ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                    sessionStart = SessionStart.New(RetrievalScope.Paper("2401.00001")),
                    savedState = savedState,
                )
            vm.setInput("q")
            vm.send()
            advanceUntilIdle()

            val created = chatDao.observeAllSessions().first().single().id
            assertEquals(created, savedState.get<Long>(AskViewModel.KEY_BOUND_SESSION_ID))
        }

    @Test
    fun `a graph artifact from New also persists the created session id`() =
        runTest(dispatcher) {
            val graph =
                RelationGraph(
                    nodes =
                        listOf(
                            RelationNode("2401.00001", "Center", isCenter = true),
                            RelationNode("2402.00002", "Neighbor", inLibrary = true),
                        ),
                    edges = listOf(RelationEdge("2401.00001", "2402.00002", RelationEdgeKind.SIMILAR, 0.8)),
                )
            val savedState = SavedStateHandle()
            val vm =
                vm(
                    cloudProvider { error("provider must not be called for an app-drawn artifact") },
                    selected = ProviderId.CLAUDE,
                    relationGraphSource = RelationGraphSource { GraphResult.Ready(graph) },
                    sessionStart = SessionStart.New(RetrievalScope.Paper("2401.00001")),
                    savedState = savedState,
                )
            vm.runGraphArtifact("Map relationships")
            advanceUntilIdle()

            val created = chatDao.observeAllSessions().first().single().id
            assertEquals(created, savedState.get<Long>(AskViewModel.KEY_BOUND_SESSION_ID))
        }

    @Test
    fun `a second start is a no-op - the first binding wins`() =
        runTest(dispatcher) {
            val a = seedSession(lastMessageAt = 1_000)
            val b = seedSession(lastMessageAt = 2_000)
            seedMessage(a, "a turn", at = 1)
            seedMessage(b, "b turn", at = 2)
            val vm =
                vm(
                    onDeviceProvider { flowOf(ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                    sessionStart = SessionStart.Resume(RetrievalScope.Paper("2401.00001"), a),
                )
            advanceUntilIdle()
            // The bind-once guard is why the route must never be launchSingleTop: a reused
            // entry's second start() (here: for session b) must not rebind the surface.
            vm.start(SessionStart.Resume(RetrievalScope.Paper("2401.00001"), b))
            advanceUntilIdle()
            assertEquals(listOf("a turn"), vm.uiState.value.messages.map { it.text })
        }

    @Test
    fun `hydrating is set synchronously at start and always cleared`() =
        runTest(dispatcher) {
            val session = seedSession(lastMessageAt = 1_000)
            seedMessage(session, "turn", at = 1)
            val vm =
                vm(
                    onDeviceProvider { flowOf(ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                )
            assertTrue(vm.uiState.value.hydrating, "set before hydration's coroutine is dispatched")
            advanceUntilIdle()
            assertEquals(false, vm.uiState.value.hydrating)
            assertEquals(listOf("turn"), vm.uiState.value.messages.map { it.text })

            // The New-with-no-saved-id path bails out of hydration early (nothing to hydrate):
            // the finally must still clear the flag.
            val fresh =
                vm(
                    onDeviceProvider { flowOf(ChatChunk.Done()) },
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = true,
                    sessionStart = SessionStart.New(RetrievalScope.Paper("2401.00001")),
                )
            assertTrue(fresh.uiState.value.hydrating)
            advanceUntilIdle()
            assertEquals(false, fresh.uiState.value.hydrating)
        }
}
