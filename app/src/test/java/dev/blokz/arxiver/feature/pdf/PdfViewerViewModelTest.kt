package dev.blokz.arxiver.feature.pdf

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
import dev.blokz.arxiver.core.network.pdf.PdfDownloader
import dev.blokz.arxiver.data.PaperRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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
class PdfViewerViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var db: ArxiverDatabase
    private lateinit var paperRepo: PaperRepository
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
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun `unknown paper resolves to a storage error, not a download`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404)) // paper lookup misses upstream too

            val vm =
                PdfViewerViewModel(
                    savedStateHandle = SavedStateHandle(mapOf("id" to "9999.99999")),
                    context = context,
                    pdfDownloader = PdfDownloader(OkHttpClient(), dispatchers),
                    paperRepository = paperRepo,
                    dispatchers = dispatchers,
                )

            val state = vm.uiState.first { !it.downloading }
            assertTrue(state.error is AppError.Storage)
            assertEquals(null, state.file)
        }
}
