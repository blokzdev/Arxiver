package dev.blokz.arxiver.feature.paper

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.FollowEntity
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.ExternalRef
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
import dev.blokz.arxiver.data.CategoryRepository
import dev.blokz.arxiver.data.LibraryRepository
import dev.blokz.arxiver.data.PaperRepository
import dev.blokz.arxiver.data.SemanticNeighborsRepository
import dev.blokz.arxiver.data.testOpenAlexClient
import dev.blokz.arxiver.sync.SyncScheduler
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PaperDetailViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var db: ArxiverDatabase
    private lateinit var paperRepo: PaperRepository
    private lateinit var library: LibraryRepository

    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Default
        }

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
        paperRepo = PaperRepository(client, db.paperDao(), testOpenAlexClient(server), dispatchers)
        library = LibraryRepository(db.libraryDao(), db.inboxDao())
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun vmFor(id: String) =
        PaperDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("id" to id)),
            paperRepository = paperRepo,
            libraryRepository = library,
            categoryRepository = CategoryRepository(db.categoryDao(), db.followDao(), db.inboxDao()),
            // Constructed lazily (WorkManager is only touched on syncNow(), which these tests don't call).
            syncScheduler = SyncScheduler(ApplicationProvider.getApplicationContext()),
            neighborsRepository = SemanticNeighborsRepository(db.paperDao(), db.embeddingDao(), dispatchers),
            embeddingDao = db.embeddingDao(),
        )

    private suspend fun cachePaper(id: String) {
        val p =
            Paper(
                ref = ArxivRef(ArxivId(id)),
                latestVersion = 1,
                title = "Cached $id",
                abstract = "A",
                publishedAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                primaryCategory = "cs.LG",
                categories = listOf("cs.LG"),
                authors = listOf("A"),
            )
        db.paperDao().upsertPaperWithRelations(p.toEntity(), p.authors, p.categories)
    }

    @Test
    fun `loads a cached paper without hitting the network`() =
        runBlocking {
            cachePaper("2403.00001")

            val state = vmFor("2403.00001").uiState.first { !it.loading }

            assertEquals("Cached 2403.00001", assertNotNull(state.paper).title)
            assertTrue(!state.notFound)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `unknown paper that is neither cached nor upstream is notFound`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))

            val state = vmFor("9999.99999").uiState.first { !it.loading }

            assertTrue(state.notFound)
            assertEquals(null, state.paper)
        }

    @Test
    fun `followedAuthors reflects a currently-followed author`() =
        runBlocking {
            db.followDao().insert(
                FollowEntity(
                    type = FollowEntity.TYPE_AUTHOR,
                    value = "Ada Lovelace",
                    label = "Ada Lovelace",
                    createdAt = 0,
                ),
            )
            cachePaper("2403.00001")
            val vm = vmFor("2403.00001")
            vm.uiState.first { !it.loading }

            assertTrue("Ada Lovelace" in vm.followedAuthors.first { it.isNotEmpty() })
        }

    @Test
    fun `addNote persists through the library repository`() =
        runBlocking {
            cachePaper("2403.00001")
            val vm = vmFor("2403.00001")
            vm.uiState.first { !it.loading }

            vm.addNote("a thought")

            assertEquals(listOf("a thought"), library.observeNotesFor("2403.00001").first().map { it.content })
        }

    @Test
    fun `add and remove collection membership round-trips`() =
        runBlocking {
            cachePaper("2403.00001")
            val cid = library.createCollection("Reading")
            val vm = vmFor("2403.00001")

            vm.addToCollection(cid)
            assertTrue(cid in vm.memberCollectionIds.first { it.contains(cid) })

            vm.removeFromCollection(cid)
            assertEquals(emptySet(), vm.memberCollectionIds.first { it.isEmpty() })
        }

    @Test
    fun `createCollectionWithPaper makes a collection and adds the paper`() =
        runBlocking {
            cachePaper("2403.00001")
            val vm = vmFor("2403.00001")

            vm.createCollectionWithPaper("Robotics")

            val created = library.observeCollections().first { it.isNotEmpty() }.single()
            assertEquals("Robotics", created.name)
            assertTrue(created.id in vm.memberCollectionIds.first { it.contains(created.id) })
        }

    // --- P-OA: the open-access resolver state machine ---

    /** Cache a browser-tier Research-Square paper so the VM loads it and the OA resolver becomes eligible. */
    private suspend fun cacheRsPaper() {
        val p =
            Paper(
                ref = ExternalRef(Source.RESEARCH_SQUARE, RS_NATIVE_ID),
                latestVersion = 1, title = RS_TITLE, abstract = "", publishedAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH, primaryCategory = "", categories = emptyList(),
                authors = listOf("Ahmed Abdelfattah"), doi = RS_NATIVE_ID,
                pdfUrl = "https://www.researchsquare.com/article/rs-27656/v1.pdf",
            )
        db.paperDao().upsertPaperWithRelations(p.toEntity(), p.authors, p.categories)
    }

    @Test
    fun `resolveOa drives Idle then Ready with the published PDF and provenance`() =
        runBlocking {
            cacheRsPaper()
            server.enqueue(MockResponse().setBody(OA_CROSSWALK_JSON).setHeader("Content-Type", "application/json"))
            val vm = vmFor("researchsquare:$RS_NATIVE_ID")
            vm.uiState.first { !it.loading }
            assertEquals(OaUiState.Idle, vm.oa.value)

            vm.resolveOa()

            val ready = assertIs<OaUiState.Ready>(vm.oa.first { it is OaUiState.Ready || it is OaUiState.NotFound })
            assertEquals(
                "https://sfamjournals.onlinelibrary.wiley.com/doi/pdfdirect/10.1111/1462-2920.15392",
                ready.url,
            )
            assertEquals("Environmental Microbiology", ready.journalName)
            assertTrue(ready.versionOfRecord)
        }

    @Test
    fun `resolveOa settles on NotFound with no error noise when there is no free version`() =
        runBlocking {
            cacheRsPaper()
            server.enqueue(MockResponse().setBody("""{"meta":{"count":0},"results":[]}"""))
            val vm = vmFor("researchsquare:$RS_NATIVE_ID")
            vm.uiState.first { !it.loading }

            vm.resolveOa()

            assertEquals(OaUiState.NotFound, vm.oa.first { it is OaUiState.NotFound || it is OaUiState.Ready })
        }

    @Test
    fun `a double tap issues exactly one OpenAlex call (Loading guard)`() =
        runBlocking {
            cacheRsPaper()
            server.enqueue(MockResponse().setBody(OA_CROSSWALK_JSON).setHeader("Content-Type", "application/json"))
            val vm = vmFor("researchsquare:$RS_NATIVE_ID")
            vm.uiState.first { !it.loading }

            vm.resolveOa()
            vm.resolveOa() // guarded — no second call while the first is in flight

            vm.oa.first { it is OaUiState.Ready }
            assertEquals(1, server.requestCount, "the Loading guard collapses a double tap to one call")
        }
}

private const val RS_NATIVE_ID = "10.21203/rs.3.rs-27656/v1"
private const val RS_TITLE =
    "Experimental evidence of microbial inheritance in plants and transmission routes " +
        "from seed to phyllosphere and root"

private val OA_CROSSWALK_JSON =
    """
    {
      "meta": {"count": 2},
      "results": [
        {
          "id": "https://openalex.org/W2000000001",
          "doi": "https://doi.org/10.1111/1462-2920.15392",
          "title": "$RS_TITLE",
          "type": "article",
          "cited_by_count": 216,
          "authorships": [{"author": {"display_name": "Ahmed Abdelfattah"}}],
          "primary_location": {"source": {"display_name": "Environmental Microbiology", "type": "journal"}},
          "best_oa_location": {
            "pdf_url": "https://sfamjournals.onlinelibrary.wiley.com/doi/pdfdirect/10.1111/1462-2920.15392",
            "version": "publishedVersion", "is_oa": true,
            "source": {"display_name": "Environmental Microbiology"}
          },
          "open_access": {"is_oa": true, "oa_status": "hybrid"}
        },
        {
          "id": "https://openalex.org/W2000000002",
          "doi": "https://doi.org/10.21203/rs.3.rs-27656/v1",
          "title": "$RS_TITLE",
          "type": "preprint",
          "cited_by_count": 10,
          "authorships": [{"author": {"display_name": "Ahmed Abdelfattah"}}],
          "primary_location": {"source": {"display_name": "Research Square", "type": "repository"}},
          "best_oa_location": {
            "pdf_url": "https://www.researchsquare.com/article/rs-27656/v1.pdf",
            "version": "acceptedVersion", "is_oa": true, "source": {"display_name": "Research Square"}
          },
          "open_access": {"is_oa": true, "oa_status": "green"}
        }
      ]
    }
    """.trimIndent()
