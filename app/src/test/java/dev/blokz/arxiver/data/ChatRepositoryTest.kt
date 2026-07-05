package dev.blokz.arxiver.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.chat.ChatContextAssembler
import dev.blokz.arxiver.chat.ChatPreviewBuilder
import dev.blokz.arxiver.core.ai.AiException
import dev.blokz.arxiver.core.ai.AiKeyStore
import dev.blokz.arxiver.core.ai.AiProvider
import dev.blokz.arxiver.core.ai.ChatChunk
import dev.blokz.arxiver.core.ai.ChatRequest
import dev.blokz.arxiver.core.ai.InferenceTier
import dev.blokz.arxiver.core.ai.OnDeviceEngine
import dev.blokz.arxiver.core.ai.OnDeviceProvider
import dev.blokz.arxiver.core.ai.OutputRichness
import dev.blokz.arxiver.core.ai.ProviderCapability
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.core.ai.ProviderRegistry
import dev.blokz.arxiver.core.ai.ProviderResolver
import dev.blokz.arxiver.core.ai.ToolCall
import dev.blokz.arxiver.core.ai.ToolDef
import dev.blokz.arxiver.core.ai.ToolResult
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DefaultDispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.ChatSessionEntity
import dev.blokz.arxiver.core.search.ChunkKeywordSource
import dev.blokz.arxiver.core.search.ChunkVectorSource
import dev.blokz.arxiver.core.search.RagRetriever
import dev.blokz.arxiver.core.search.RetrievalScope
import dev.blokz.arxiver.core.search.ScopedChunk
import dev.blokz.arxiver.data.tool.ToolExecution
import dev.blokz.arxiver.data.tool.ToolExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ChatRepositoryTest {
    private lateinit var db: ArxiverDatabase

    private class FakeProvider(
        override val id: ProviderId = ProviderId.CLAUDE,
        supportsTools: Boolean = false,
        private val script: () -> Flow<ChatChunk>,
    ) : AiProvider {
        override val capability =
            ProviderCapability(
                100_000,
                streaming = true,
                onDevice = false,
                requiresKey = true,
                richness = OutputRichness.FULL,
                supportsTools = supportsTools,
            )

        override fun chat(request: ChatRequest): Flow<ChatChunk> = script()
    }

    /** A ready on-device engine at a fixed tier/richness — used to prove richness threads through prepare (PA.2). */
    private class FakeOnDeviceEngine(
        override val tier: InferenceTier,
        override val richness: OutputRichness,
        private val reply: String = "",
    ) : OnDeviceEngine {
        override suspend fun isReady(): Boolean = true

        override fun generate(request: ChatRequest): Flow<ChatChunk> =
            if (reply.isEmpty()) flowOf(ChatChunk.Done()) else flowOf(ChatChunk.Delta(reply), ChatChunk.Done())
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

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun repo(
        provider: AiProvider,
        keys: Set<ProviderId> = setOf(ProviderId.CLAUDE),
        selected: ProviderId = ProviderId.CLAUDE,
        preferOnDevice: Boolean = false,
        onDeviceReady: Boolean = false,
        embed: suspend (String) -> AppResult<FloatArray> = { AppResult.Success(FloatArray(384)) },
        toolLoop: ChatToolLoop = ChatToolLoop(),
        transaction: suspend (suspend () -> Unit) -> Unit = { it() },
    ): ChatRepository {
        val registry = ProviderRegistry(listOf(provider), FakeKeyStore(keys))
        val resolver = ProviderResolver(registry, { selected }, { preferOnDevice }, { onDeviceReady })
        var t = 0L
        return ChatRepository(
            chatDao = db.chatDao(),
            ragRetriever = emptyRetriever,
            providerResolver = resolver,
            assembler = ChatContextAssembler(),
            previewBuilder = ChatPreviewBuilder(),
            embedQuery = embed,
            dispatchers = DefaultDispatcherProvider(),
            clock = { t++ },
            toolLoop = toolLoop,
            transaction = transaction,
        )
    }

    private suspend fun ChatRepository.prepared(
        scope: RetrievalScope,
        sessionId: Long?,
        question: String,
    ): PreparedChat = (prepare(scope, sessionId, question, includeNotes = true) as ChatPrepareResult.Ready).prepared

    @Test
    fun `streaming persists the user turn and a completed assistant turn`() =
        runTest {
            val repo =
                repo(FakeProvider { flowOf(ChatChunk.Delta("Hello "), ChatChunk.Delta("world"), ChatChunk.Done()) })

            val prep = repo.prepared(RetrievalScope.Paper("p1"), null, "q")
            assertEquals(ProviderId.CLAUDE, prep.providerId)
            assertTrue(prep.isCloud)
            repo.stream(prep).toList()

            val sid = repo.observeSessions(RetrievalScope.Paper("p1")).first().single().id
            val msgs = db.chatDao().messagesFor(sid)
            assertEquals(listOf("q", "Hello world"), msgs.map { it.content })
            assertEquals(listOf("complete", "complete"), msgs.map { it.status })
        }

    // --- P-Tools PT.0: the tool loop through the repository ---

    private class RepoEchoExecutor : ToolExecutor {
        override fun toolDefs(): List<ToolDef> =
            listOf(ToolDef("echo", "echo", buildJsonObject { put("type", "object") }))

        override suspend fun execute(call: ToolCall): ToolExecution =
            ToolExecution(ToolResult(call.id, call.name, "{}"), call.inputJson, "echoed", egress = false)
    }

    private fun toolProvider(vararg scripts: List<ChatChunk>): FakeProvider {
        var i = 0
        return FakeProvider(supportsTools = true) {
            val s = scripts.getOrElse(i++) { error("provider over-called") }
            flow { s.forEach { emit(it) } }
        }
    }

    @Test
    fun `a tool turn persists a complete assistant row and its tool_invocations`() =
        runTest {
            val provider =
                toolProvider(
                    listOf(ChatChunk.ToolUse("t1", "echo", """{"text":"hi"}"""), ChatChunk.Done("tool_use")),
                    listOf(ChatChunk.Delta("answer"), ChatChunk.Done()),
                )
            val repo = repo(provider, toolLoop = ChatToolLoop(RepoEchoExecutor()))

            val prep = repo.prepared(RetrievalScope.Paper("p1"), null, "q")
            repo.stream(prep).toList()

            val sid = repo.observeSessions(RetrievalScope.Paper("p1")).first().single().id
            val assistant = db.chatDao().messagesFor(sid).last()
            assertEquals("answer", assistant.content)
            assertEquals("complete", assistant.status)
            val invocations = db.chatDao().toolInvocationsForMessage(assistant.id)
            assertEquals(1, invocations.size)
            assertEquals("echo", invocations.single().toolName)
            assertFalse(invocations.single().egress)
        }

    @Test
    fun `an error after a tool round persists error and writes zero tool rows (dangling guard)`() =
        runTest {
            var i = 0
            val provider =
                FakeProvider(supportsTools = true) {
                    if (i++ == 0) {
                        flow {
                            emit(ChatChunk.ToolUse("t1", "echo", "{}"))
                            emit(ChatChunk.Done("tool_use"))
                        }
                    } else {
                        flow { throw AiException(AppError.Offline) }
                    }
                }
            val repo = repo(provider, toolLoop = ChatToolLoop(RepoEchoExecutor()))

            val prep = repo.prepared(RetrievalScope.Paper("p1"), null, "q")
            assertFailsWith<AiException> { repo.stream(prep).toList() }

            val sid = repo.observeSessions(RetrievalScope.Paper("p1")).first().single().id
            val assistant = db.chatDao().messagesFor(sid).last()
            assertEquals("error", assistant.status)
            // The tool ran in-memory, but persistTerminal never fired → NO durable tool rows.
            assertEquals(0, db.chatDao().toolInvocationsForMessage(assistant.id).size)
        }

    @Test
    fun `a failure in the terminal write persists error, not incomplete`() =
        runTest {
            val repo =
                repo(
                    FakeProvider { flowOf(ChatChunk.Delta("hi"), ChatChunk.Done()) },
                    transaction = { throw RuntimeException("db down") },
                )

            val prep = repo.prepared(RetrievalScope.Paper("p1"), null, "q")
            assertFailsWith<RuntimeException> { repo.stream(prep).toList() }

            val sid = repo.observeSessions(RetrievalScope.Paper("p1")).first().single().id
            assertEquals("error", db.chatDao().messagesFor(sid).last().status)
        }

    @Test
    fun `a failed stream persists the partial assistant turn as error and rethrows`() =
        runTest {
            val repo =
                repo(
                    FakeProvider {
                        flow {
                            emit(ChatChunk.Delta("par"))
                            throw AiException(AppError.Offline)
                        }
                    },
                )

            val prep = repo.prepared(RetrievalScope.Paper("p1"), null, "q")
            assertFailsWith<AiException> { repo.stream(prep).toList() }

            val sid = repo.observeSessions(RetrievalScope.Paper("p1")).first().single().id
            val assistant = db.chatDao().messagesFor(sid).last()
            assertEquals("par", assistant.content)
            assertEquals("error", assistant.status)
        }

    @Test
    fun `insertArtifactTurn persists a complete user and assistant turn without a provider`() =
        runTest {
            // The provider script throws if touched — an app-drawn artifact must never call it (P-Atlas PA.1).
            val repo = repo(FakeProvider { error("provider must not be called for an artifact turn") })
            val mermaid = "```mermaid\ngraph TD\n  n0[\"x\"]\n```"

            val sid = repo.insertArtifactTurn(RetrievalScope.Paper("p1"), null, "Map relationships", mermaid)

            val msgs = db.chatDao().messagesFor(sid)
            assertEquals(listOf("Map relationships", mermaid), msgs.map { it.content })
            assertEquals(listOf("complete", "complete"), msgs.map { it.status })

            // A second call reuses the same session (no orphan sessions).
            val sid2 = repo.insertArtifactTurn(RetrievalScope.Paper("p1"), sid, "again", "x")
            assertEquals(sid, sid2)
            assertEquals(4, db.chatDao().messagesFor(sid).size)
        }

    @Test
    fun `no usable provider resolves to NotConfigured and persists nothing`() =
        runTest {
            val repo = repo(FakeProvider { flowOf(ChatChunk.Done()) }, keys = emptySet())

            val result = repo.prepare(RetrievalScope.Paper("p1"), null, "q", includeNotes = true)
            assertTrue(result is ChatPrepareResult.NotConfigured)
            assertTrue(repo.observeSessions(RetrievalScope.Paper("p1")).first().isEmpty())
        }

    @Test
    fun `an existing session is reused and prior turns feed back as history`() =
        runTest {
            val repo = repo(FakeProvider { flowOf(ChatChunk.Delta("a1"), ChatChunk.Done()) })

            repo.stream(repo.prepared(RetrievalScope.Paper("p1"), null, "q1")).toList()
            val sid = repo.observeSessions(RetrievalScope.Paper("p1")).first().single().id

            val prep2 = repo.prepared(RetrievalScope.Paper("p1"), sid, "q2")
            assertTrue(prep2.request.messages.any { it.content.contains("q1") }, "prior turn folded into context")
            repo.stream(prep2).toList()

            assertEquals(4, db.chatDao().messagesFor(sid).size)
            assertEquals(1, repo.observeSessions(RetrievalScope.Paper("p1")).first().size)
        }

    @Test
    fun `on-device Gemma threads STRUCTURED richness into the assembled system prompt (PA_2)`() =
        runTest {
            // A ready Gemma engine reports STRUCTURED; prepare() must resolve it and shape the system prompt.
            val onDevice =
                OnDeviceProvider(
                    listOf(FakeOnDeviceEngine(InferenceTier.GEMMA, OutputRichness.STRUCTURED)),
                    DefaultDispatcherProvider(),
                )
            val repo =
                repo(onDevice, keys = emptySet(), preferOnDevice = true, onDeviceReady = true)

            val prep = repo.prepared(RetrievalScope.Paper("p1"), null, "compare A and B")

            assertEquals(ProviderId.ON_DEVICE, prep.providerId)
            assertFalse(prep.isCloud)
            val system = prep.request.system!!
            assertTrue(system.startsWith(ChatContextAssembler.SYSTEM_PROMPT))
            assertTrue(
                system.contains(ChatContextAssembler.STRUCTURED_RICH_ADDENDUM),
                "Gemma's STRUCTURED tier adds the table nudge",
            )
            assertFalse(
                system.contains(ChatContextAssembler.CLOUD_RICH_ADDENDUM),
                "on-device never gets the cloud LaTeX/Mermaid invitation",
            )
        }

    // --- P-Atlas PA.4: the STRUCTURED TABLE:: intermediate is rendered to a valid table on persist ---

    private val tableReply =
        "Both stages differ.\n" +
            "TABLE::\nStage ~|~ Role\nCold ~|~ boot [1]\nRL ~|~ refine [2]\n::TABLE\n" +
            "The cold stage stabilizes RL [1].\n" +
            "FOLLOWUPS:: what is cold-start | how does RL refine | which dataset"

    @Test
    fun `a STRUCTURED on-device turn persists a rendered GFM table, FOLLOWUPS stripped`() =
        runTest {
            val onDevice =
                OnDeviceProvider(
                    listOf(FakeOnDeviceEngine(InferenceTier.GEMMA, OutputRichness.STRUCTURED, reply = tableReply)),
                    DefaultDispatcherProvider(),
                )
            val repo = repo(onDevice, keys = emptySet(), preferOnDevice = true, onDeviceReady = true)

            repo.stream(repo.prepared(RetrievalScope.Paper("p1"), null, "compare the stages")).toList()

            val sid = repo.observeSessions(RetrievalScope.Paper("p1")).first().single().id
            val persisted = db.chatDao().messagesFor(sid).last().content
            assertTrue(persisted.contains("| Stage | Role |\n| --- | --- |"), "the TABLE:: block became a GFM table")
            assertTrue(persisted.contains("| Cold | boot [1] |"), "rows + citations survive")
            assertFalse(persisted.contains("~|~"), "the sentinel intermediate is gone")
            assertFalse(persisted.contains("TABLE::"), "the fence is gone")
            assertFalse(persisted.contains("FOLLOWUPS"), "the follow-up sentinel is stripped (composition)")
            assertTrue(persisted.startsWith("Both stages differ."), "surrounding prose preserved")
        }

    @Test
    fun `a cloud (FULL) turn never transforms the TABLE intermediate — byte-identical (the cloud invariant)`() =
        runTest {
            // A cloud provider emitting the same sentinel text must persist it untouched (FULL ≠ STRUCTURED).
            val structuredText = "Both stages differ.\nTABLE::\nStage ~|~ Role\nCold ~|~ boot [1]\n::TABLE"
            val repo = repo(FakeProvider { flowOf(ChatChunk.Delta(structuredText), ChatChunk.Done()) })

            repo.stream(repo.prepared(RetrievalScope.Paper("p1"), null, "q")).toList()

            val sid = repo.observeSessions(RetrievalScope.Paper("p1")).first().single().id
            assertEquals(structuredText, db.chatDao().messagesFor(sid).last().content, "FULL is untransformed")
        }

    @Test
    fun `settleStructured (the shared display+persist gate) transforms STRUCTURED but leaves FULL byte-identical`() =
        runTest {
            // The display seam (AskViewModel.settleFollowUps) and the persist seam call this SAME helper,
            // so pinning it here covers both seams' gating with real PreparedChats.
            val sentinelBody = "Both stages.\nTABLE::\nStage ~|~ Role\nCold ~|~ boot [1]\nRL ~|~ refine [2]\n::TABLE"

            val onDevice =
                OnDeviceProvider(
                    listOf(FakeOnDeviceEngine(InferenceTier.GEMMA, OutputRichness.STRUCTURED)),
                    DefaultDispatcherProvider(),
                )
            val structuredPrep =
                repo(onDevice, keys = emptySet(), preferOnDevice = true, onDeviceReady = true)
                    .prepared(RetrievalScope.Paper("p1"), null, "q")
            assertTrue(settleStructured(structuredPrep, sentinelBody).contains("| Stage | Role |"))

            val fullPrep =
                repo(FakeProvider { flowOf(ChatChunk.Done()) }).prepared(RetrievalScope.Paper("p2"), null, "q")
            assertEquals(sentinelBody, settleStructured(fullPrep, sentinelBody), "cloud FULL is never transformed")
        }

    @Test
    fun `a Done with no text persists the assistant turn as error - never an empty complete row`() =
        runTest {
            val repo = repo(FakeProvider { flowOf(ChatChunk.Done()) })

            repo.stream(repo.prepared(RetrievalScope.Paper("p1"), null, "q")).toList()

            val sid = repo.observeSessions(RetrievalScope.Paper("p1")).first().single().id
            val assistant = db.chatDao().messagesFor(sid).last()
            assertEquals("", assistant.content)
            assertEquals("error", assistant.status, "an empty-complete row would dodge every ghost filter")
        }

    @Test
    fun `ensureSession returns the exact id the streamed turn then writes into`() =
        runTest {
            val repo = repo(FakeProvider { flowOf(ChatChunk.Delta("a"), ChatChunk.Done()) })

            val prep = repo.prepared(RetrievalScope.Paper("p1"), null, "q")
            val sid = repo.ensureSession(prep)
            assertEquals(sid, repo.ensureSession(prep.copy(sessionId = sid)), "an existing binding is identity")

            repo.stream(prep.copy(sessionId = sid)).toList()
            assertEquals(listOf(sid), repo.observeSessions(RetrievalScope.Paper("p1")).first().map { it.id })
            assertEquals(2, db.chatDao().messagesFor(sid).size)
        }

    @Test
    fun `sessionScope reconstructs paper and collection scopes and nulls the rest`() =
        runTest {
            val repo = repo(FakeProvider { flowOf(ChatChunk.Done()) })

            suspend fun seed(
                kind: String,
                scopeId: String,
            ) = db.chatDao().insertSession(
                ChatSessionEntity(
                    scope = kind,
                    scopeId = scopeId,
                    providerId = "CLAUDE",
                    createdAt = 1,
                    lastMessageAt = 1,
                ),
            )
            val paper = seed(ChatSessionEntity.SCOPE_PAPER, "math/0211159")
            val collection = seed(ChatSessionEntity.SCOPE_COLLECTION, "7")
            val broken = seed(ChatSessionEntity.SCOPE_COLLECTION, "not-a-number")

            assertEquals(RetrievalScope.Paper("math/0211159"), repo.sessionScope(paper))
            assertEquals(RetrievalScope.Collection(7), repo.sessionScope(collection))
            assertNull(repo.sessionScope(broken), "an unparsable collection id must pop, not crash")
            assertNull(repo.sessionScope(999L), "a deleted session resolves null (stale back stack)")
        }

    @Test
    fun `observeSession emits the current title and reflects a rename`() =
        runTest {
            val repo = repo(FakeProvider { flowOf(ChatChunk.Done()) })
            val sid =
                db.chatDao().insertSession(
                    ChatSessionEntity(
                        scope = "PAPER",
                        scopeId = "p1",
                        providerId = "CLAUDE",
                        createdAt = 1,
                        lastMessageAt = 1,
                    ),
                )
            assertEquals(null, repo.observeSession(sid).first()?.title)
            repo.renameSession(sid, "Named")
            assertEquals("Named", repo.observeSession(sid).first { it?.title == "Named" }?.title)
        }

    @Test
    fun `setPinned reorders observeSessionRows live - pinned first`() =
        runTest {
            val repo = repo(FakeProvider { flowOf(ChatChunk.Done()) })

            fun seed(
                scopeId: String,
                at: Long,
            ) = ChatSessionEntity(
                scope = "PAPER",
                scopeId = scopeId,
                providerId = "CLAUDE",
                createdAt = at,
                lastMessageAt = at,
            )
            val older = db.chatDao().insertSession(seed("p1", 1))
            db.chatDao().insertSession(seed("p2", 99)) // fresher, unpinned
            assertEquals(older, repo.observeSessionRows().first().last().session.id) // older is last (unpinned)

            repo.setPinned(older, true)
            assertEquals(older, repo.observeSessionRows().first { it.first().session.pinned }.first().session.id)
        }
}
