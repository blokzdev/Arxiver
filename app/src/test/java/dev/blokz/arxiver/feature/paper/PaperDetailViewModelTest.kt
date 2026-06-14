package dev.blokz.arxiver.feature.paper

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
import dev.blokz.arxiver.data.LibraryRepository
import dev.blokz.arxiver.data.PaperRepository
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
        db = Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java).build()
        val client =
            ArxivApiClient(
                httpClient = OkHttpClient(),
                rateLimiter = ArxivRateLimiter(minSpacingMs = 0),
                dispatchers = dispatchers,
                baseUrl = server.url("/api/query").toString(),
                retryDelaysMs = emptyList(),
            )
        paperRepo = PaperRepository(client, db.paperDao())
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
            embeddingDao = db.embeddingDao(),
        )

    private suspend fun cachePaper(id: String) {
        val p =
            Paper(
                id = ArxivId(id),
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
}
