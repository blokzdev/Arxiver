package dev.blokz.arxiver.feature.pdf

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.ReadingPositionEntity
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
import dev.blokz.arxiver.core.network.pdf.PdfDownloader
import dev.blokz.arxiver.core.network.pdf.PdfHostPolicy
import dev.blokz.arxiver.data.PaperRepository
import dev.blokz.arxiver.data.ReadingProgressRepository
import dev.blokz.arxiver.data.testOpenAlexClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PdfViewerViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var db: ArxiverDatabase
    private lateinit var paperRepo: PaperRepository
    private lateinit var readingRepo: ReadingProgressRepository
    private lateinit var context: Context

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
        context = ApplicationProvider.getApplicationContext()
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
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
        readingRepo = ReadingProgressRepository(db.readingPositionDao())
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
        Dispatchers.resetMain()
    }

    /** Completes when the PFT.5.7 PDF-body nudge fires — so a test can await it (Success) or assert it never
     *  completed (failure/restore paths). Fresh per test (JUnit builds a new instance per @Test). */
    private val pdfIndexed = kotlinx.coroutines.CompletableDeferred<Pair<String, Int>>()
    private val pdfTrigger =
        dev.blokz.arxiver.rag.PdfBodyIndexTrigger { storageId, version -> pdfIndexed.complete(storageId to version) }

    private fun vmFor(id: String): PdfViewerViewModel =
        PdfViewerViewModel(
            savedStateHandle = SavedStateHandle(mapOf("id" to id)),
            context = context,
            pdfDownloader =
                PdfDownloader(
                    OkHttpClient(),
                    PdfHostPolicy(ArxivRateLimiter(minSpacingMs = 0), ArxivRateLimiter(minSpacingMs = 0)),
                    dispatchers,
                ),
            paperRepository = paperRepo,
            readingProgressRepository = readingRepo,
            applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            pdfBodyIndexTrigger = pdfTrigger,
            settingsRepository = dev.blokz.arxiver.data.SettingsRepository(context),
            dispatchers = dispatchers,
        ).apply { positionSaveDebounceMs = 0L }

    /** Seed a paper whose PDF downloads from the mock server (so load() reaches the success branch). */
    private suspend fun seedPaperWithLocalPdf(
        id: String,
        version: Int = 1,
    ) {
        server.enqueue(MockResponse().setResponseCode(200).setBody("%PDF-1.4 fake body"))
        val paper =
            Paper(
                ref = ArxivRef(ArxivId(id)),
                latestVersion = version,
                title = "T",
                abstract = "A",
                publishedAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                primaryCategory = "cs.LG",
                categories = listOf("cs.LG"),
                authors = listOf("A"),
                fetchedAt = Instant.EPOCH,
            )
        val entity = paper.toEntity().copy(pdfUrl = server.url("/$id.pdf").toString())
        db.paperDao().upsertPaperWithRelations(entity, listOf("A"), listOf("cs.LG"))
    }

    private suspend fun seedPosition(
        id: String,
        version: Int,
        page: Int,
        offset: Int,
        fraction: Float,
    ) = readingRepo.upsert(
        ReadingPositionEntity(
            paperId = id,
            surface = ReadingPositionEntity.SURFACE_PDF,
            version = version,
            anchorId = null,
            offsetPx = offset,
            fraction = fraction,
            pageIndex = page,
            finished = false,
            updatedAt = 100,
        ),
    )

    @Test
    fun `unknown paper resolves to a storage error, not a download`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            val vm = vmFor("9999.99999")
            val state = vm.uiState.first { !it.downloading }
            assertTrue(state.error is AppError.Storage)
            assertNull(state.file)
        }

    @Test
    fun `a successful PDF download nudges body-indexing once, with the right id and version`() =
        runBlocking {
            seedPaperWithLocalPdf("2401.00001", version = 3)
            val vm = vmFor("2401.00001")
            vm.uiState.first { !it.downloading && it.file != null }

            val fired = kotlinx.coroutines.withTimeout(3_000) { pdfIndexed.await() }
            assertEquals("2401.00001" to 3, fired)
        }

    @Test
    fun `a load that never reaches a download does not nudge body-indexing`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            val vm = vmFor("9999.99999")
            vm.uiState.first { !it.downloading }
            assertTrue(!pdfIndexed.isCompleted, "the PDF nudge fires only on a successful download")
        }

    @Test
    fun `readerThemeMode reflects the persisted preference and setReaderTheme write-throughs`() =
        runBlocking {
            val settings = dev.blokz.arxiver.data.SettingsRepository(context)
            settings.setReaderThemeMode(dev.blokz.arxiver.data.ReaderThemeMode.DARK)
            seedPaperWithLocalPdf("2401.00007")
            val vm = vmFor("2401.00007")

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

    @Test
    fun `merely opening a PDF writes no reading-position row`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            val vm = vmFor("9999.99999")
            vm.uiState.first { !it.downloading }
            assertNull(db.readingPositionDao().get("9999.99999", ReadingPositionEntity.SURFACE_PDF))
        }

    @Test
    fun `a genuine scroll sample persists a durable pdf row (continuous fraction, never finished)`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            val vm = vmFor("2401.00001")
            vm.uiState.first { !it.downloading }

            vm.onPositionChanged(page = 2, offset = 50, fraction = 0.3f)

            val row =
                withTimeout(2_000) {
                    var r = db.readingPositionDao().get("2401.00001", ReadingPositionEntity.SURFACE_PDF)
                    while (r == null) {
                        delay(10)
                        r = db.readingPositionDao().get("2401.00001", ReadingPositionEntity.SURFACE_PDF)
                    }
                    r
                }
            assertEquals(2, row.pageIndex)
            assertEquals(50, row.offsetPx)
            assertEquals(0.3f, row.fraction, 1e-6f)
            assertTrue(!row.finished, "PDF finished is never inferred")
        }

    @Test
    fun `load restores the stored position for the matching version`() =
        runBlocking {
            seedPaperWithLocalPdf("2401.00001", version = 1)
            seedPosition("2401.00001", version = 1, page = 3, offset = 20, fraction = 0.4f)

            val vm = vmFor("2401.00001")
            val state = vm.uiState.first { !it.downloading }

            assertNotNull(state.file, "the mock PDF downloaded")
            assertEquals(PdfResumeTarget(3, 20), state.initialPosition)
        }

    @Test
    fun `a version skew does not restore but keeps the row`() =
        runBlocking {
            seedPaperWithLocalPdf("2401.00001", version = 2)
            seedPosition("2401.00001", version = 1, page = 3, offset = 20, fraction = 0.4f) // stale version

            val vm = vmFor("2401.00001")
            vm.uiState.first { !it.downloading }

            assertNotNull(
                db.readingPositionDao().get("2401.00001", ReadingPositionEntity.SURFACE_PDF),
                "the row is kept (still drives the shelf)",
            )
            assertNull(vm.uiState.value.initialPosition, "a version skew soft-misses to top")
        }
}
