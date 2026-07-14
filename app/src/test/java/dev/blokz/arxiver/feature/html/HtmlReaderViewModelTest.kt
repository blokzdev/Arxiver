package dev.blokz.arxiver.feature.html

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.ai.Fidelity
import dev.blokz.arxiver.core.ai.FidelityReport
import dev.blokz.arxiver.core.ai.HtmlFetchResult
import dev.blokz.arxiver.core.ai.HtmlFetcher
import dev.blokz.arxiver.core.ai.HtmlImageFetcher
import dev.blokz.arxiver.core.ai.HtmlSource
import dev.blokz.arxiver.core.ai.HtmlStorage
import dev.blokz.arxiver.core.ai.InlinedImage
import dev.blokz.arxiver.core.ai.ReaderDocument
import dev.blokz.arxiver.core.ai.ReaderImage
import dev.blokz.arxiver.core.ai.ReaderPosition
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.ReadingPositionEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
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

    /** A fake image fetcher that returns canned bytes and emits each via the callback (HtmlImageFetcher.fetchAll is `open`). */
    private fun imageFetcherReturning(result: Map<String, InlinedImage>): HtmlImageFetcher =
        object : HtmlImageFetcher(OkHttpClient(), ArxivRateLimiter(minSpacingMs = 0), dispatchers) {
            override suspend fun fetchAll(
                images: List<ReaderImage>,
                onImage: (String, InlinedImage) -> Unit,
            ): Map<String, InlinedImage> {
                result.forEach { (k, v) -> onImage(k, v) }
                return result
            }
        }

    private fun vmWith(
        fetcher: HtmlFetcher,
        imageFetcher: HtmlImageFetcher = imageFetcherReturning(emptyMap()),
    ) = HtmlReaderViewModel(
        savedStateHandle = SavedStateHandle(mapOf("id" to PAPER_ID)),
        htmlFetcher = fetcher,
        htmlImageFetcher = imageFetcher,
        htmlStorage = storage,
        paperRepository = paperRepo,
        libraryRepository = dev.blokz.arxiver.data.LibraryRepository(db.libraryDao(), db.inboxDao()),
        dispatchers = dispatchers,
        applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO),
        bodyIndexTrigger = dev.blokz.arxiver.rag.BodyIndexTrigger { _, _ -> },
        readingProgressRepository = dev.blokz.arxiver.data.ReadingProgressRepository(db.readingPositionDao()),
        settingsRepository = dev.blokz.arxiver.data.SettingsRepository(ApplicationProvider.getApplicationContext()),
    ).apply { shelfDebounceMs = 0 }

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

    // --- P-Read: the HTML "Continue reading" shelf heartbeat (the PH.6 sidecar funnel stays untouched) ---

    private suspend fun htmlShelfRow() = db.readingPositionDao().get(PAPER_ID, ReadingPositionEntity.SURFACE_HTML)

    private suspend fun awaitHtmlShelfRow(): ReadingPositionEntity =
        kotlinx.coroutines.withTimeout(2_000) {
            var r = htmlShelfRow()
            while (r == null) {
                kotlinx.coroutines.delay(10)
                r = htmlShelfRow()
            }
            r
        }

    @Test
    fun `opening the reader without scrolling writes no shelf row`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            val vm = vmWith(fetcherReturning(HtmlFetchResult.Native(doc(HtmlSource.NATIVE))))
            vm.uiState.first { !it.loading }
            assertNull(htmlShelfRow(), "a mere open is not 'continue reading'")
        }

    @Test
    fun `a jump does not write a shelf row`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            val vm = vmWith(fetcherReturning(HtmlFetchResult.Native(doc(HtmlSource.NATIVE))))
            vm.uiState.first { !it.loading }

            vm.onJump("S1")
            kotlinx.coroutines.delay(50) // give the 0-debounce heartbeat a chance; a jump never feeds the shelf probe
            assertNull(htmlShelfRow(), "a TOC/citation jump is navigation, not progress")
        }

    @Test
    fun `a genuine scroll probe writes a shelf row, not finished`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            val vm = vmWith(fetcherReturning(HtmlFetchResult.Native(doc(HtmlSource.NATIVE))))
            vm.uiState.first { !it.loading }

            vm.onPositionProbed(ReaderPosition(anchorId = null, offsetCssPx = 40, fraction = 0.5f))

            val row = awaitHtmlShelfRow()
            assertEquals(0.5f, row.fraction, 1e-6f)
            assertTrue(!row.finished, "one probe is not finished")
        }

    @Test
    fun `sustained high dwell sets finished then a below-floor probe resets it`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            val vm = vmWith(fetcherReturning(HtmlFetchResult.Native(doc(HtmlSource.NATIVE))))
            vm.uiState.first { !it.loading }

            vm.onPositionProbed(ReaderPosition(null, 0, 0.97f))
            vm.onPositionProbed(ReaderPosition(null, 0, 0.98f)) // two consecutive high probes → finished
            kotlinx.coroutines.withTimeout(2_000) { while (!awaitHtmlShelfRow().finished) kotlinx.coroutines.delay(10) }

            vm.onPositionProbed(ReaderPosition(null, 0, 0.4f)) // scrolled back up → the paper reappears
            kotlinx.coroutines.withTimeout(2_000) { while (awaitHtmlShelfRow().finished) kotlinx.coroutines.delay(10) }
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

    @Test
    fun `figures are fetched and inlined as data uris in the second phase`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404)) // paper lookup misses → version 1
            val withImages =
                ReaderDocument(
                    bodyHtml = """<figure><img data-img-key="k1"></figure>""",
                    fidelity = FidelityReport(Fidelity.OK, null, 0, 0, 0),
                    anchors = emptyList(),
                    source = HtmlSource.NATIVE,
                    images = listOf(ReaderImage("k1", "https://arxiv.org/html/2412.19437v1/x1.png")),
                )
            val vm =
                vmWith(
                    fetcherReturning(HtmlFetchResult.Native(withImages)),
                    imageFetcherReturning(mapOf("k1" to InlinedImage("png", "AAAA"))),
                )

            // Phase 2 re-exposes the body with the figure inlined as a data: URI.
            val state = vm.uiState.first { it.doc?.bodyHtml?.contains("data:image/png;base64,AAAA") == true }
            assertNull(state.error)
            assertTrue(state.doc!!.bodyHtml.contains("<img"), "the figure is a real <img> again")
        }

    @Test
    fun `a poisoned cache body is dropped and the document re-fetched`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404)) // paper lookup misses → version 1
            // Seed the version-1 cache with a body carrying an external host (simulated tamper).
            storage.store(
                ArxivId(PAPER_ID),
                1,
                HtmlSource.NATIVE,
                """<p><img src="https://evil.example/track.gif"></p>""",
            )
            val vm = vmWith(fetcherReturning(HtmlFetchResult.Native(doc(HtmlSource.NATIVE))))

            val state = vm.uiState.first { !it.loading }
            assertNull(state.error)
            assertTrue(state.doc!!.bodyHtml.contains("body"), "the clean re-fetch is shown")
            assertTrue(!state.doc!!.bodyHtml.contains("evil.example"), "the poisoned cache is not rendered")
        }

    // --- PH.6: anchors on cache hits + the reading-position policy ------------------------------

    private fun bodyWithAnchors() =
        "<section id=\"S1\"><h2 class=\"ltx_title\">Introduction</h2></section>" +
            "<section id=\"S2\"><h2 class=\"ltx_title\">Method</h2></section>"

    private suspend fun seedCache(body: String = bodyWithAnchors()) {
        storage.store(ArxivId(PAPER_ID), 1, HtmlSource.NATIVE, body)
    }

    @Test
    fun `a cache hit carries real re-derived anchors, not an empty list`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            seedCache()
            val vm = vmWith(fetcherReturning(HtmlFetchResult.Error(AppError.Offline)))

            val state = vm.uiState.first { !it.loading }
            assertEquals(listOf("S1", "S2"), state.doc!!.anchors.map { it.id })
        }

    @Test
    fun `a pre-stored sidecar seeds the restore target on a cache hit`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            seedCache()
            storage.storePosition(ArxivId(PAPER_ID), 1, ReaderPosition("S2", 120, 0.6f))
            val vm = vmWith(fetcherReturning(HtmlFetchResult.Error(AppError.Offline)))

            vm.uiState.first { !it.loading }
            assertEquals(ReaderPosition("S2", 120, 0.6f), vm.restoreTarget())
        }

    @Test
    fun `probes validate the anchor against the current doc and clamp values`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            seedCache()
            val vm = vmWith(fetcherReturning(HtmlFetchResult.Error(AppError.Offline)))
            vm.uiState.first { !it.loading }

            vm.onPositionProbed(ReaderPosition("NOT_A_REAL_ANCHOR", -9, 3f))

            assertEquals(ReaderPosition(null, 0, 1f), vm.restoreTarget())
        }

    @Test
    fun `an explicit jump holds the slot against probes until the settle window passes`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            seedCache()
            val vm = vmWith(fetcherReturning(HtmlFetchResult.Error(AppError.Offline)))
            vm.uiState.first { !it.loading }

            var clock = 100_000L
            vm.now = { clock }

            vm.onJump("S2")
            assertEquals("S2", vm.restoreTarget()!!.anchorId)

            // Inside the settle window: the probe must NOT demote the jump.
            clock += 500
            vm.onPositionProbed(ReaderPosition("S1", 10, 0.1f))
            assertEquals("S2", vm.restoreTarget()!!.anchorId)

            // A re-apply resets the settle clock; still held.
            vm.onRestoreApplied()
            clock += 2_000
            vm.onPositionProbed(ReaderPosition("S1", 10, 0.1f))
            assertEquals("S2", vm.restoreTarget()!!.anchorId)

            // Past the (reset) window: the probe demotes — normal reading resumes.
            clock += 3_000
            vm.onPositionProbed(ReaderPosition("S1", 10, 0.1f))
            assertEquals("S1", vm.restoreTarget()!!.anchorId)
        }

    @Test
    fun `onTocSelect claims the slot and emits the jump effect with its label`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            seedCache()
            val vm = vmWith(fetcherReturning(HtmlFetchResult.Error(AppError.Offline)))
            vm.uiState.first { !it.loading }

            vm.onTocSelect("S2", "Method")

            val effect = vm.effects.first()
            assertTrue(effect is HtmlReaderEffect.JumpToAnchor)
            assertEquals("S2", effect.anchorId)
            assertEquals("Method", effect.label)
            assertEquals("S2", vm.restoreTarget()!!.anchorId)
        }

    @Test
    fun `a settled probe is persisted to the sidecar keyed on the served version`() =
        runBlocking {
            // The persist loop's debounce delay() runs on viewModelScope (Main). The default test
            // Main is an UnconfinedTestDispatcher whose VIRTUAL clock real-time sleeping never
            // advances — use a real dispatcher for this case so the debounce actually elapses.
            Dispatchers.resetMain()
            Dispatchers.setMain(Dispatchers.Default)
            server.enqueue(MockResponse().setResponseCode(404))
            seedCache() // served version = 1
            val vm = vmWith(fetcherReturning(HtmlFetchResult.Error(AppError.Offline)))
            vm.uiState.first { !it.loading }

            vm.onPositionProbed(ReaderPosition("S1", 42, 0.2f))

            // Poll for the debounced (1s) write + IO hop.
            var pos: ReaderPosition? = null
            repeat(50) {
                if (pos == null) {
                    kotlinx.coroutines.delay(100)
                    pos = storage.readPosition(ArxivId(PAPER_ID), 1)
                }
            }
            assertEquals(ReaderPosition("S1", 42, 0.2f), pos)
        }

    @Test
    fun `readerThemeMode reflects the shared preference and setReaderTheme write-throughs`() =
        runBlocking {
            val settings = dev.blokz.arxiver.data.SettingsRepository(ApplicationProvider.getApplicationContext())
            settings.setReaderThemeMode(dev.blokz.arxiver.data.ReaderThemeMode.DARK)
            val vm = vmWith(fetcherReturning(HtmlFetchResult.Native(doc(HtmlSource.NATIVE))))

            assertEquals(
                dev.blokz.arxiver.data.ReaderThemeMode.DARK,
                vm.readerThemeMode.first { it == dev.blokz.arxiver.data.ReaderThemeMode.DARK },
            )

            vm.setReaderTheme(dev.blokz.arxiver.data.ReaderThemeMode.LIGHT)
            assertEquals(
                dev.blokz.arxiver.data.ReaderThemeMode.LIGHT,
                settings.readerThemeMode.first { it == dev.blokz.arxiver.data.ReaderThemeMode.LIGHT },
            )
        }

    private companion object {
        const val PAPER_ID = "2412.19437"
    }
}
