package dev.blokz.arxiver.feature.html

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.ai.Fidelity
import dev.blokz.arxiver.core.ai.FidelityReport
import dev.blokz.arxiver.core.ai.HtmlFetchResult
import dev.blokz.arxiver.core.ai.HtmlFetcher
import dev.blokz.arxiver.core.ai.HtmlSource
import dev.blokz.arxiver.core.ai.HtmlStorage
import dev.blokz.arxiver.core.ai.ReaderDocument
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
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
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HtmlReaderViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var db: ArxiverDatabase
    private lateinit var paperRepo: PaperRepository
    private lateinit var storage: HtmlStorage

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
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
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
        // A fresh empty temp dir guarantees a cache miss, so load() always reaches the fetcher.
        storage = HtmlStorage(Files.createTempDirectory("htmlvm").toFile(), dispatchers)
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
        Dispatchers.resetMain()
    }

    /** A fetcher that ignores the network and returns one canned outcome (HtmlFetcher.fetch is `open`). */
    private fun fetcherReturning(result: HtmlFetchResult): HtmlFetcher =
        object : HtmlFetcher(OkHttpClient(), ArxivRateLimiter(minSpacingMs = 0), dispatchers) {
            override suspend fun fetch(
                id: ArxivId,
                version: Int,
            ): HtmlFetchResult = result
        }

    private fun vmWith(fetcher: HtmlFetcher) =
        HtmlReaderViewModel(
            savedStateHandle = SavedStateHandle(mapOf("id" to PAPER_ID)),
            htmlFetcher = fetcher,
            htmlStorage = storage,
            paperRepository = paperRepo,
            dispatchers = dispatchers,
        )

    private fun doc(source: HtmlSource) =
        ReaderDocument(
            bodyHtml = "<p>body</p>",
            fidelity = FidelityReport(Fidelity.OK, null, 0, 0, 0),
            anchors = emptyList(),
            source = source,
        )

    @Test
    fun `native success exposes the native reader document`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404)) // paper lookup misses → version 1
            val vm = vmWith(fetcherReturning(HtmlFetchResult.Native(doc(HtmlSource.NATIVE))))

            val state = vm.uiState.first { !it.loading }
            assertNull(state.error)
            assertEquals(HtmlSource.NATIVE, state.doc?.source)
        }

    @Test
    fun `ar5iv fallback exposes the ar5iv document for the banner`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            val vm = vmWith(fetcherReturning(HtmlFetchResult.Ar5iv(doc(HtmlSource.AR5IV))))

            val state = vm.uiState.first { !it.loading }
            assertNull(state.error)
            assertEquals(HtmlSource.AR5IV, state.doc?.source)
        }

    @Test
    fun `no usable HTML emits a one-shot fallback-to-PDF effect`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            val vm = vmWith(fetcherReturning(HtmlFetchResult.FallbackToPdf))

            val effect = vm.effects.first()
            assertTrue(effect is HtmlReaderEffect.FallbackToPdf)
            assertEquals(PAPER_ID, effect.id)
        }

    @Test
    fun `a transport error surfaces in state without a fallback`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            val vm = vmWith(fetcherReturning(HtmlFetchResult.Error(AppError.Offline)))

            val state = vm.uiState.first { !it.loading }
            assertEquals(AppError.Offline, state.error)
            assertNull(state.doc)
        }

    @Test
    fun `openPdfInstead emits the fallback effect on demand`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            val vm = vmWith(fetcherReturning(HtmlFetchResult.Error(AppError.Offline)))
            vm.uiState.first { !it.loading } // let init settle (Error path sends no effect)

            vm.openPdfInstead()

            val effect = vm.effects.first()
            assertTrue(effect is HtmlReaderEffect.FallbackToPdf)
            assertEquals(PAPER_ID, effect.id)
        }

    private companion object {
        const val PAPER_ID = "2412.19437"
    }
}
