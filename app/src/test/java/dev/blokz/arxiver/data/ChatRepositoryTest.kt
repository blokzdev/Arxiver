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
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DefaultDispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.search.ChunkKeywordSource
import dev.blokz.arxiver.core.search.ChunkVectorSource
import dev.blokz.arxiver.core.search.RagRetriever
import dev.blokz.arxiver.core.search.RetrievalScope
import dev.blokz.arxiver.core.search.ScopedChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ChatRepositoryTest {
    private lateinit var db: ArxiverDatabase

    private class FakeProvider(
        override val id: ProviderId = ProviderId.CLAUDE,
        private val script: () -> Flow<ChatChunk>,
    ) : AiProvider {
        override val capability =
            ProviderCapability(
                100_000,
                streaming = true,
                onDevice = false,
                requiresKey = true,
                richness = OutputRichness.FULL,
            )

        override fun chat(request: ChatRequest): Flow<ChatChunk> = script()
    }

    /** A ready on-device engine at a fixed tier/richness — used to prove richness threads through prepare (PA.2). */
    private class FakeOnDeviceEngine(
        override val tier: InferenceTier,
        override val richness: OutputRichness,
    ) : OnDeviceEngine {
        override suspend fun isReady(): Boolean = true

        override fun generate(request: ChatRequest): Flow<ChatChunk> = flowOf(ChatChunk.Done())
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
}
