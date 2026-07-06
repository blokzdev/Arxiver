package dev.blokz.arxiver.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.FollowEntity
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

    private fun worker(attempt: Int): FollowSyncWorker {
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    FollowSyncWorker(appContext, workerParameters, db.followDao(), db.paperDao(), db.inboxDao(), client)
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
}
