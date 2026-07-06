package dev.blokz.arxiver.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.FollowEntity
import dev.blokz.arxiver.core.model.ExternalRef
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.network.PreprintBackend
import dev.blokz.arxiver.core.network.PreprintBackendRegistry
import dev.blokz.arxiver.core.network.PreprintHit
import dev.blokz.arxiver.core.network.PreprintPage
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

/**
 * Guards the spinner fix: a persistently failing follow must not retry forever (which
 * pinned the Today sync indicator). Retries below the cap, gives up (success) at the cap.
 */
@RunWith(RobolectricTestRunner::class)
class FollowSyncWorkerTest {
    private lateinit var server: MockWebServer
    private lateinit var db: ArxiverDatabase
    private lateinit var context: Context
    private lateinit var client: ArxivApiClient

    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Default
        }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        context = ApplicationProvider.getApplicationContext()
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                // Synchronous executors so the InvalidationTracker refresh can't race db.close() and
                // leak an "Illegal connection pointer" into the next test (memory
                // robolectric-room-sync-executors).
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        client =
            ArxivApiClient(
                httpClient = OkHttpClient(),
                rateLimiter = ArxivRateLimiter(minSpacingMs = 0),
                dispatchers = dispatchers,
                baseUrl = server.url("/api/query").toString(),
                retryDelaysMs = emptyList(),
            )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    /** A [PreprintBackendRegistry] whose every backend returns [page] — used for the non-arXiv branch. */
    private fun registryReturning(page: AppResult<PreprintPage>): PreprintBackendRegistry {
        val backend =
            object : PreprintBackend {
                override suspend fun browse(
                    source: Source,
                    category: String?,
                    sinceIso: String,
                    cursor: String?,
                ): AppResult<PreprintPage> = page
            }
        return PreprintBackendRegistry(bioRxivBackend = backend, openAlexBackend = backend)
    }

    private fun worker(
        attempt: Int,
        backends: PreprintBackendRegistry = registryReturning(AppResult.Success(PreprintPage(emptyList(), null))),
    ): FollowSyncWorker {
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    FollowSyncWorker(
                        appContext,
                        workerParameters,
                        db.followDao(),
                        db.paperDao(),
                        db.inboxDao(),
                        client,
                        backends,
                    )
            }
        return TestListenableWorkerBuilder<FollowSyncWorker>(context)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(factory)
            .build()
    }

    @Test
    fun `failing follow retries below the cap and gives up at it`() =
        runBlocking {
            db.followDao().insert(
                FollowEntity(type = FollowEntity.TYPE_CATEGORY, value = "cs.LG", label = "ML", createdAt = 0),
            )
            repeat(4) { server.enqueue(MockResponse().setResponseCode(404)) } // every fetch fails

            assertTrue(worker(attempt = 0).doWork() is ListenableWorker.Result.Retry)
            assertTrue(worker(attempt = 3).doWork() is ListenableWorker.Result.Success)
        }

    @Test
    fun `no follows is an immediate success`() =
        runBlocking {
            assertTrue(worker(attempt = 0).doWork() is ListenableWorker.Result.Success)
        }

    @Test
    fun `a non-arXiv follow rides its preprint backend into the inbox`() =
        runBlocking {
            // A biorxiv-origin follow must bypass the Atom client entirely and ride PreprintBackend.
            db.followDao().insert(
                FollowEntity(
                    type = FollowEntity.TYPE_CATEGORY,
                    value = "neuroscience",
                    label = "Neuro",
                    createdAt = 0,
                    origin = Source.BIORXIV.wire,
                ),
            )
            val hit =
                PreprintHit(
                    origin = Source.BIORXIV,
                    doi = "10.1101/2026.01.02.680000",
                    title = "A brain paper",
                    abstract = "We studied brains.",
                    authors = listOf("Ada Lovelace"),
                    publishedIso = "2026-01-02",
                    oaPdfUrl = "https://www.biorxiv.org/content/10.1101/2026.01.02.680000v1.full.pdf",
                    version = "1",
                )
            val backends = registryReturning(AppResult.Success(PreprintPage(listOf(hit), nextCursor = null)))

            assertTrue(worker(attempt = 0, backends = backends).doWork() is ListenableWorker.Result.Success)

            // The external paper landed under its origin-blind ExternalRef storage id (no arXiv fork).
            assertEquals(1, db.paperDao().count())
            assertTrue(db.inboxDao().activePaperIds().contains(ExternalRef(Source.BIORXIV, hit.doi).storageId))
        }

    @Test
    fun `a firehose source that fills the first-sync limit on page 1 issues exactly one browse`() =
        runBlocking {
            // A whole-source SSRN follow (value = "") spans all fields — a firehose. It must NOT page 5× per
            // sync: the do/while stops once page 1 fills FIRST_SYNC_LIMIT. Guards the OpenAlex credit budget.
            db.followDao().insert(
                FollowEntity(
                    type = FollowEntity.TYPE_CATEGORY,
                    value = "",
                    label = "All of SSRN",
                    createdAt = 0,
                    origin = Source.SSRN.wire,
                ),
            )
            var calls = 0
            val firehose =
                object : PreprintBackend {
                    override suspend fun browse(
                        source: Source,
                        category: String?,
                        sinceIso: String,
                        cursor: String?,
                    ): AppResult<PreprintPage> {
                        calls++
                        val hits =
                            (1..20).map {
                                PreprintHit(
                                    origin = source,
                                    doi = "10.9999/ssrn.$it",
                                    title = "t$it",
                                    abstract = "a",
                                    authors = emptyList(),
                                    publishedIso = null,
                                    oaPdfUrl = null,
                                )
                            }
                        // Non-null nextCursor: the worker WOULD page again if the short-circuit didn't fire.
                        return AppResult.Success(PreprintPage(hits, nextCursor = "more"))
                    }
                }
            val backends = PreprintBackendRegistry(bioRxivBackend = firehose, openAlexBackend = firehose)

            assertTrue(worker(attempt = 0, backends = backends).doWork() is ListenableWorker.Result.Success)
            assertEquals(1, calls, "a page-1 fill of FIRST_SYNC_LIMIT must short-circuit paging")
        }
}
