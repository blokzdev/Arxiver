package dev.blokz.arxiver.feature.search

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.fts.LocalKeywordSearch
import dev.blokz.arxiver.core.ml.EmbeddingService
import dev.blokz.arxiver.core.ml.ModelDownloader
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
import dev.blokz.arxiver.core.search.VectorIndex
import dev.blokz.arxiver.data.CategoryRepository
import dev.blokz.arxiver.data.LibraryRepository
import dev.blokz.arxiver.data.PaperRepository
import dev.blokz.arxiver.data.testOpenAlexClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The PE.3 metering contract, asserted against a REAL repository and a real [MockWebServer] — network discipline
 * is proven by counting actual requests, not by trusting fakes. Every OpenAlex call must be an explicit submit;
 * switching source clears state and never auto-searches; the arXiv pagination snapshot cannot leak across scopes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SearchViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var db: ArxiverDatabase
    private lateinit var repo: PaperRepository
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var categoryRepository: CategoryRepository

    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Default
        }

    private val golden: String by lazy {
        checkNotNull(javaClass.classLoader.getResource("openalex/chemrxiv_search.json")).readText()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        server =
            MockWebServer().apply {
                // Path-routed: /oa serves the OpenAlex golden page; anything else 404s (an arXiv call in an
                // external-scope test is a BUG and must fail loudly, not be absorbed by a queue).
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            if (request.path.orEmpty().startsWith("/oa")) {
                                MockResponse().setBody(golden).setHeader("Content-Type", "application/json")
                            } else {
                                MockResponse().setResponseCode(404)
                            }
                    }
                start()
            }
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        val arxivClient =
            ArxivApiClient(
                httpClient = OkHttpClient(),
                rateLimiter = ArxivRateLimiter(minSpacingMs = 0),
                dispatchers = dispatchers,
                baseUrl = server.url("/api/query").toString(),
                retryDelaysMs = emptyList(),
            )
        repo = PaperRepository(arxivClient, db.paperDao(), testOpenAlexClient(server))
        libraryRepository = LibraryRepository(db.libraryDao(), db.inboxDao())
        categoryRepository = CategoryRepository(db.categoryDao(), db.followDao(), db.inboxDao())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        server.shutdown()
    }

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()): SearchViewModel =
        SearchViewModel(
            paperRepository = repo,
            localKeywordSearch = LocalKeywordSearch(db.searchDao()),
            vectorIndex = VectorIndex(db.embeddingDao()),
            embeddingService =
                EmbeddingService(
                    modelDownloader = ModelDownloader(OkHttpClient(), dispatchers, File("build/tmp/never-used")),
                    tokenizerProvider = { error("the semantic leg must never run in these tests") },
                    dispatchers = dispatchers,
                ),
            modelDownloader = ModelDownloader(OkHttpClient(), dispatchers, File("build/tmp/never-used")),
            searchDao = db.searchDao(),
            corpusBodyRetriever =
                dev.blokz.arxiver.core.search.CorpusBodyRetriever(
                    dev.blokz.arxiver.core.search.DaoCorpusBodySource(db.chunkEmbeddingDao()),
                ),
            chunkEmbeddingDao = db.chunkEmbeddingDao(),
            libraryRepository = libraryRepository,
            categoryRepository = categoryRepository,
            savedStateHandle = handle,
        )

    @Test
    fun `switching source clears results and fires ZERO network`() =
        runTest {
            val vm = viewModel()
            vm.setOnlineSource(Source.SSRN)

            assertEquals(Source.SSRN, vm.uiState.value.onlineSource)
            assertTrue(vm.uiState.value.results.isEmpty())
            assertEquals(0, server.requestCount, "a source switch must never auto-search (metered)")
        }

    @Test
    fun `an external submit issues exactly one OpenAlex call and lands un-paginated`() =
        runTest {
            val vm = viewModel()
            vm.setOnlineSource(Source.CHEMRXIV)
            vm.onQueryChange("catalysis")
            vm.submit()
            vm.uiState.first { !it.searching }

            assertEquals(1, server.requestCount, "one explicit submit = one metered call")
            assertTrue(server.takeRequest().path.orEmpty().startsWith("/oa"), "the call is the OpenAlex leg")
            assertEquals(2, vm.uiState.value.results.size)
            assertNull(vm.uiState.value.nextStart, "un-paginated v1 — auto-load-more is structurally inert")
        }

    @Test
    fun `loadMore on an external scope performs zero repository calls`() =
        runTest {
            val vm = viewModel()
            vm.setOnlineSource(Source.CHEMRXIV)
            vm.onQueryChange("catalysis")
            vm.submit()
            vm.uiState.first { !it.searching }
            val after = server.requestCount

            vm.loadMore()

            assertEquals(after, server.requestCount, "nextStart=null must make loadMore a no-op")
        }

    @Test
    fun `typing never touches the network`() =
        runTest {
            val vm = viewModel()
            vm.setOnlineSource(Source.SSRN)
            "monetary policy".forEachIndexed { i, _ -> vm.onQueryChange("monetary policy".take(i + 1)) }

            assertEquals(0, server.requestCount, "keystrokes feed only the local debounced leg")
        }

    @Test
    fun `the online source is restored from SavedStateHandle and a bad value degrades to arXiv`() {
        val restored = viewModel(SavedStateHandle(mapOf("online_source" to "SSRN")))
        assertEquals(Source.SSRN, restored.uiState.value.onlineSource)

        val corrupt = viewModel(SavedStateHandle(mapOf("online_source" to "NOT_A_SOURCE")))
        assertEquals(Source.ARXIV, corrupt.uiState.value.onlineSource)
    }

    @Test
    fun `saveHit persists the paper before the library row and saves under the winning id`() =
        runTest {
            val vm = viewModel()
            vm.setOnlineSource(Source.CHEMRXIV)
            vm.onQueryChange("catalysis")
            vm.submit()
            vm.uiState.first { !it.searching }
            val hit = vm.uiState.value.results.first()

            vm.saveHit(hit)

            // The papers row exists (no FK crash) and the library entry keys the WINNING id.
            assertEquals(1, db.paperDao().count())
            assertTrue(db.libraryDao().allPaperIds().contains(hit.ref.storageId))
        }
}
