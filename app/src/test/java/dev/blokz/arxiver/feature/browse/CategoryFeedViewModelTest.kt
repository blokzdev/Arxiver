package dev.blokz.arxiver.feature.browse

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
import dev.blokz.arxiver.data.LibraryRepository
import dev.blokz.arxiver.data.PaperRepository
import dev.blokz.arxiver.data.testOpenAlexClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CategoryFeedViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var db: ArxiverDatabase
    private lateinit var paperRepo: PaperRepository
    private lateinit var libraryRepo: LibraryRepository

    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Default
        }

    private fun fixtureXml() =
        requireNotNull(javaClass.getResourceAsStream("/arxiv_feed_sample.xml")).readBytes().decodeToString()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply { start() }
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                // Synchronous executors so the InvalidationTracker refresh can't race db.close() and
                // leak an "Illegal connection pointer" into the next test (memory
                // robolectric-room-sync-executors).
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        val client =
            ArxivApiClient(
                httpClient = OkHttpClient(),
                rateLimiter = ArxivRateLimiter(minSpacingMs = 0),
                dispatchers = dispatchers,
                baseUrl = server.url("/api/query").toString(),
                retryDelaysMs = emptyList(),
            )
        paperRepo = PaperRepository(client, db.paperDao(), testOpenAlexClient(server))
        libraryRepo = LibraryRepository(db.libraryDao(), db.inboxDao())
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun vm() =
        CategoryFeedViewModel(
            savedStateHandle = SavedStateHandle(mapOf("code" to "cs.LG", "title" to "Machine Learning")),
            paperRepository = paperRepo,
            libraryRepository = libraryRepo,
        )

    @Test
    fun `loads the first page on init`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(fixtureXml()))

            val state = vm().uiState.first { !it.loading && it.papers.isNotEmpty() }

            assertEquals(2, state.papers.size)
            assertEquals(null, state.error)
        }

    @Test
    fun `upstream failure surfaces an error state`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))

            val state = vm().uiState.first { !it.loading && it.error != null }

            assertEquals(AppError.Upstream(404), state.error)
            assertTrue(state.papers.isEmpty())
        }

    @Test
    fun `saveAll puts the feed's selected papers into the library`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(fixtureXml()))
            val model = vm()
            val ids = model.uiState.first { it.papers.isNotEmpty() }.papers.map { it.id.value }

            model.saveAll(ids)

            assertEquals(
                ids.toSet(),
                libraryRepo.observeLibrary().first { it.size == ids.size }.map { it.paper.id.value }.toSet(),
            )
        }
}
