package dev.blokz.arxiver.data

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
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.ChatSessionEntity
import dev.blokz.arxiver.core.search.ChunkKeywordSource
import dev.blokz.arxiver.core.search.ChunkVectorSource
import dev.blokz.arxiver.core.search.RagRetriever
import dev.blokz.arxiver.core.search.RetrievalScope
import dev.blokz.arxiver.core.search.ScopedChunk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The PC.3 delayed-commit delete: a delete is hidden immediately by the VM, then committed on
 * the injected [appScope] after [ChatRepository.UNDO_WINDOW_MS] so it survives the VM being
 * cleared (tab switch). Undo cancels the pending commit. Driven by the test scheduler, with the
 * repo's `appScope` set to `backgroundScope` so `advanceTimeBy` controls the window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatRepositoryDeleteTest {
    private lateinit var db: ArxiverDatabase

    private class TestDispatchers(private val d: CoroutineDispatcher) : DispatcherProvider {
        override val io: CoroutineDispatcher get() = d
        override val default: CoroutineDispatcher get() = d
        override val main: CoroutineDispatcher get() = d
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
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
    }

    @After
    fun tearDown() = db.close()

    private fun repo(
        dispatcher: CoroutineDispatcher,
        appScope: CoroutineScope,
    ): ChatRepository =
        ChatRepository(
            chatDao = db.chatDao(),
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
            appScope = appScope,
        )

    private suspend fun seedSession(): Long =
        db.chatDao().insertSession(
            ChatSessionEntity(
                scope = "PAPER",
                scopeId = "2401.00001",
                providerId = "CLAUDE",
                createdAt = 1,
                lastMessageAt = 1,
            ),
        )

    @Test
    fun `a scheduled delete commits after the undo window`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo = repo(dispatcher, backgroundScope)
            val id = seedSession()

            repo.scheduleDelete(id)
            advanceTimeBy(ChatRepository.UNDO_WINDOW_MS + 1)
            advanceUntilIdle()

            assertNull(db.chatDao().sessionById(id), "the delete commits after the window")
        }

    @Test
    fun `undo cancels a pending delete before the window elapses`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo = repo(dispatcher, backgroundScope)
            val id = seedSession()

            repo.scheduleDelete(id)
            advanceTimeBy(ChatRepository.UNDO_WINDOW_MS / 2)
            repo.undoDelete(id)
            advanceTimeBy(ChatRepository.UNDO_WINDOW_MS) // past the original deadline
            advanceUntilIdle()

            assertNotNull(db.chatDao().sessionById(id), "undo kept the session alive")
        }

    @Test
    fun `the commit runs on the app scope - not cancelled when the VM's scope would be`() =
        runTest {
            // appScope = backgroundScope (survives the test body like the app scope survives the
            // VM). No viewModelScope is involved; the delete commits purely from appScope.
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo = repo(dispatcher, backgroundScope)
            val id = seedSession()

            repo.scheduleDelete(id)
            advanceTimeBy(ChatRepository.UNDO_WINDOW_MS + 1)
            advanceUntilIdle()

            assertNull(db.chatDao().sessionById(id))
        }

    @Test
    fun `re-scheduling the same id replaces the old job and commits once`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo = repo(dispatcher, backgroundScope)
            val id = seedSession()

            repo.scheduleDelete(id)
            repo.undoDelete(id) // cancel
            repo.scheduleDelete(id) // re-schedule cleanly
            advanceTimeBy(ChatRepository.UNDO_WINDOW_MS + 1)
            advanceUntilIdle()

            assertNull(db.chatDao().sessionById(id), "a clean single commit, no crash/leak")
        }
}
