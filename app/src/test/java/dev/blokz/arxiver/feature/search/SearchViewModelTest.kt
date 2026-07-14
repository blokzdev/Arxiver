package dev.blokz.arxiver.feature.search

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.ChunkEmbeddingEntity
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.database.fts.LocalKeywordSearch
import dev.blokz.arxiver.core.ml.EmbeddingService
import dev.blokz.arxiver.core.ml.ModelDownloader
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
import dev.blokz.arxiver.core.search.CorpusBodyRetriever
import dev.blokz.arxiver.core.search.DaoCorpusBodySource
import dev.blokz.arxiver.core.search.VectorIndex
import dev.blokz.arxiver.data.CategoryRepository
import dev.blokz.arxiver.data.LibraryRepository
import dev.blokz.arxiver.data.PaperRepository
import dev.blokz.arxiver.data.testOpenAlexClient
import dev.blokz.arxiver.sync.EmbeddingWorker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

    /**
     * P-FullText exclude fix (device-found 2026-07-14): the "Also found in full text" leg dedupes against papers
     * actually MATCHED BY METADATA — keyword hits ∪ the GATED/displayed `fused` results — NOT the raw top-K
     * semantic KNN. A body-only paper that is only a FAINT semantic neighbour (below the 70% display gate, shown
     * in neither the main list nor — pre-fix — this section) must still surface here. Regression guard: with the
     * old `semanticLeg` exclude, `faint` is swallowed and `bodyOnlyResults` is empty.
     */
    @Test
    fun `a faint semantic neighbour with a body-only match still surfaces under full text`() =
        runTest {
            // Share the scheduler so the local leg's debounce + the off-path body-leg coroutine advance here.
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

            // Query term "quokka" lives ONLY in each paper's BODY (title=id, abstract="") → zero keyword hits.
            seedPaperWithBody("displayed", floatArrayOf(1f, 0f, 0f, 0f), "the quokka roams the reserve")
            seedPaperWithBody("faint", floatArrayOf(0.15f, 0.99f, 0f, 0f), "quokka quokka quokka")

            // A model DIRECTORY holding the expected model file → ModelDownloader.initialState() is Ready, so the
            // semantic leg runs (its embedQuery is faked below; nothing actually loads the file).
            val readyModelDir =
                File.createTempFile("bgedir", "").apply {
                    delete()
                    mkdirs()
                    deleteOnExit()
                }
            File(readyModelDir, ModelDownloader.ModelSpec.BGE_SMALL_EN_V15_Q8.fileName)
                .apply {
                    writeText("stub")
                    deleteOnExit()
                }

            val vm =
                SearchViewModel(
                    paperRepository = repo,
                    localKeywordSearch = LocalKeywordSearch(db.searchDao()),
                    vectorIndex = VectorIndex(db.embeddingDao()),
                    embeddingService =
                        EmbeddingService(
                            ModelDownloader(OkHttpClient(), dispatchers, File("build/tmp/never-used")),
                            tokenizerProvider = { error("query embedding is faked via the embedQuery seam") },
                            dispatchers = dispatchers,
                        ),
                    modelDownloader = ModelDownloader(OkHttpClient(), dispatchers, readyModelDir),
                    searchDao = db.searchDao(),
                    corpusBodyRetriever = CorpusBodyRetriever(DaoCorpusBodySource(db.chunkEmbeddingDao())),
                    chunkEmbeddingDao = db.chunkEmbeddingDao(),
                    libraryRepository = libraryRepository,
                    categoryRepository = categoryRepository,
                    savedStateHandle = SavedStateHandle(),
                ).apply {
                    // Canned query vector: cosine 1.0 with `displayed`, ≈0.15 with `faint`.
                    embedQuery = { AppResult.Success(floatArrayOf(1f, 0f, 0f, 0f)) }
                }

            vm.onQueryChange("quokka")
            advanceUntilIdle()

            val state = vm.uiState.value
            // `displayed` is the gated main result; `faint` (below the gate) is the body-only match the fix keeps.
            assertTrue(state.localResults.any { it.paper.title == "displayed" }, "displayed is a main-list hit")
            assertEquals(
                listOf("faint"),
                state.bodyOnlyResults.map { it.paper.title },
                "the faint below-gate neighbour surfaces under full text; the displayed hit is deduped out",
            )
        }

    private suspend fun seedPaperWithBody(
        id: String,
        vector: FloatArray,
        body: String,
    ) {
        db.paperDao().upsertPaper(paperEntity(id))
        db.embeddingDao().upsert(
            PaperEmbeddingEntity(
                paperId = id,
                vector = PaperEmbeddingEntity.floatsToBlob(vector),
                model = "test",
                dim = vector.size,
            ),
        )
        db.chunkEmbeddingDao().insert(
            listOf(
                ChunkEmbeddingEntity(
                    paperId = id,
                    chunkText = body,
                    vector = PaperEmbeddingEntity.floatsToBlob(vector),
                    model = EmbeddingWorker.MODEL_NAME,
                    dim = vector.size,
                    sourceKind = ChunkEmbeddingEntity.SOURCE_BODY,
                    ordinal = 0,
                    embeddedAt = 0,
                ),
            ),
        )
    }

    private fun paperEntity(id: String) =
        PaperEntity(
            id = id, latestVersion = 1, title = id, abstract = "", publishedAt = 0, updatedAt = 0,
            primaryCategory = "", authorsLine = "", comment = null, journalRef = null, doi = null,
            pdfUrl = "", citationCount = null, s2PaperId = null, source = "arxiv", fetchedAt = 0,
            embeddedAt = 0, citationsSyncedAt = null,
        )
}
