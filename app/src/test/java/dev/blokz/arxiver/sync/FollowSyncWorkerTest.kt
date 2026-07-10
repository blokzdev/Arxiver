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
import dev.blokz.arxiver.core.database.entity.InboxItemEntity
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.ExternalRef
import dev.blokz.arxiver.core.model.Paper
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // --- P-FeedPolish PFP.1: cross-source de-dup at feed-ingest ---

    private fun page(vararg hits: PreprintHit) = AppResult.Success(PreprintPage(hits.toList(), nextCursor = null))

    private fun chemFollow() =
        FollowEntity(
            type = FollowEntity.TYPE_CATEGORY,
            value = "fields/16",
            label = "Chem",
            createdAt = 0,
            origin = Source.CHEMRXIV.wire,
        )

    private fun hit(
        origin: Source,
        doi: String,
        arxivId: String? = null,
        oaPdfUrl: String? = null,
        fieldName: String? = null,
    ) = PreprintHit(
        origin = origin,
        doi = doi,
        title = "t",
        abstract = "a",
        authors = emptyList(),
        publishedIso = null,
        oaPdfUrl = oaPdfUrl,
        arxivId = arxivId,
        fieldName = fieldName,
    )

    private suspend fun seed(p: Paper) = db.paperDao().upsertPaperWithRelations(p.toEntity(), p.authors, p.categories)

    @Test
    fun `an OpenAlex hit carrying an arXiv cross-id collapses onto the existing arXiv row (no fork, no clobber)`() =
        runBlocking {
            // A real arXiv paper already stored via the native path, with rich metadata.
            seed(
                Paper(
                    ref = ArxivRef(ArxivId("2403.09999")), latestVersion = 2, title = "Rich arXiv Title",
                    abstract = "a", publishedAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
                    primaryCategory = "cs.LG", categories = listOf("cs.LG"), authors = listOf("A"),
                    pdfUrl = "https://arxiv.org/pdf/2403.09999v2",
                ),
            )
            db.followDao().insert(chemFollow())
            // The SAME paper served by a chemRxiv follow — its OpenAlex work carries an arXiv location.
            val h =
                hit(
                    Source.CHEMRXIV,
                    "10.26434/chemrxiv-2024-x",
                    arxivId = "https://arxiv.org/abs/2403.09999",
                    oaPdfUrl = "https://chemrxiv.org/x.pdf",
                )

            worker(attempt = 0, backends = registryReturning(page(h))).doWork()

            assertEquals(1, db.paperDao().count(), "the cross-posted paper does not fork")
            assertTrue(db.inboxDao().activePaperIds().contains("2403.09999"), "inbox keyed under the bare arXiv id")
            // Degraded-metadata guard: the rich native-arXiv row was NOT clobbered by the OpenAlex hit.
            assertEquals(
                "https://arxiv.org/pdf/2403.09999v2",
                assertNotNull(db.paperDao().paperById("2403.09999")).pdfUrl,
            )
        }

    @Test
    fun `an OpenAlex hit with no arXiv cross-id keys under its source ExternalRef`() =
        runBlocking {
            db.followDao().insert(chemFollow())

            worker(
                attempt = 0,
                backends = registryReturning(page(hit(Source.CHEMRXIV, "10.26434/chemrxiv-2024-y"))),
            ).doWork()

            assertEquals(1, db.paperDao().count())
            assertTrue(
                db.inboxDao().activePaperIds().contains(
                    ExternalRef(Source.CHEMRXIV, "10.26434/chemrxiv-2024-y").storageId,
                ),
            )
        }

    @Test
    fun `a hit sharing a DOI with a stored paper reuses that row, case-insensitively (no fork)`() =
        runBlocking {
            seed(
                Paper(
                    ref = ExternalRef(Source.BIORXIV, "10.1101/z"), latestVersion = 1, title = "Bio", abstract = "a",
                    publishedAt = Instant.EPOCH, updatedAt = Instant.EPOCH, primaryCategory = "",
                    categories = emptyList(),
                    authors = listOf("A"), doi = "10.1101/Z", pdfUrl = "https://www.biorxiv.org/z.pdf",
                ),
            )
            db.followDao().insert(
                FollowEntity(
                    type = FollowEntity.TYPE_CATEGORY,
                    value = "",
                    label = "SSRN",
                    createdAt = 0,
                    origin = Source.SSRN.wire,
                ),
            )

            // Same DOI (lowercase), no arXiv cross-id → reuse the stored bioRxiv row via paperIdByDoi COLLATE NOCASE.
            worker(attempt = 0, backends = registryReturning(page(hit(Source.SSRN, "10.1101/z")))).doWork()

            assertEquals(1, db.paperDao().count(), "the same DOI reuses the stored row (case-insensitive)")
            assertTrue(db.inboxDao().activePaperIds().contains("biorxiv:10.1101/z"))
        }

    @Test
    fun `a hit reuses a stored paper whose DOI is VERSIONED — the doi_norm key (PE_2 regression)`() =
        runBlocking {
            // Stored raw with a `.vN` suffix. Before PE.2, `paperIdByDoi` matched the RAW doi column while the
            // worker queried with `normalizeDoi(...)` (suffix stripped) — so this row was invisible and the hit
            // FORKED into a second paper. `doi_norm` makes both sides agree.
            seed(
                Paper(
                    ref = ExternalRef(Source.BIORXIV, "10.1101/zed.v3"), latestVersion = 1, title = "Bio",
                    abstract = "a", publishedAt = Instant.EPOCH, updatedAt = Instant.EPOCH, primaryCategory = "",
                    categories = emptyList(), authors = listOf("A"), doi = "10.1101/zed.v3",
                    pdfUrl = "https://www.biorxiv.org/zed.pdf",
                ),
            )
            db.followDao().insert(
                FollowEntity(
                    type = FollowEntity.TYPE_CATEGORY,
                    value = "",
                    label = "SSRN",
                    createdAt = 0,
                    origin = Source.SSRN.wire,
                ),
            )

            // Same paper, un-versioned DOI, surfaced through a different source.
            worker(attempt = 0, backends = registryReturning(page(hit(Source.SSRN, "10.1101/zed")))).doWork()

            assertEquals(1, db.paperDao().count(), "the versioned stored DOI is reused, not forked")
            assertTrue(db.inboxDao().activePaperIds().contains("biorxiv:10.1101/zed.v3"))
        }

    // --- P-Explorer PE.0: the source's discipline label reaches the stored paper ---

    @Test
    fun `a hit's field name is stored as the paper's primary category (not blank)`() =
        runBlocking {
            db.followDao().insert(chemFollow())
            val h = hit(Source.CHEMRXIV, "10.26434/chemrxiv-2024-cat", fieldName = "Chemistry")

            worker(attempt = 0, backends = registryReturning(page(h))).doWork()

            val stored = assertNotNull(db.paperDao().paperById(ExternalRef(Source.CHEMRXIV, h.doi).storageId))
            assertEquals(
                "Chemistry",
                stored.primaryCategory,
                "the OpenAlex Field label reaches papers.primary_category",
            )
        }

    @Test
    fun `a hit with no field name still stores a blank category (no crash, no fake label)`() =
        runBlocking {
            db.followDao().insert(chemFollow())
            val h = hit(Source.CHEMRXIV, "10.26434/chemrxiv-2024-nocat", fieldName = null)

            worker(attempt = 0, backends = registryReturning(page(h))).doWork()

            val stored = assertNotNull(db.paperDao().paperById(ExternalRef(Source.CHEMRXIV, h.doi).storageId))
            assertEquals("", stored.primaryCategory)
        }

    // --- P-FeedPolish PFP.3: follow health streak ---

    private fun bioFollow(streak: Int = 0) =
        FollowEntity(
            type = FollowEntity.TYPE_CATEGORY,
            value = "neuroscience",
            label = "Neuro",
            createdAt = 0,
            origin = Source.BIORXIV.wire,
            emptySyncStreak = streak,
        )

    private suspend fun bioStreak() =
        assertNotNull(db.followDao().find(FollowEntity.TYPE_CATEGORY, "neuroscience", Source.BIORXIV.wire))

    @Test
    fun `an empty-delivery sync bumps the health streak once per sync`() =
        runBlocking {
            db.followDao().insert(bioFollow())
            // Default backend returns an empty page → Fetched(0) every sync.
            repeat(FollowEntity.EMPTY_STREAK_WARN) { worker(attempt = 0).doWork() }
            assertEquals(
                FollowEntity.EMPTY_STREAK_WARN,
                bioStreak().emptySyncStreak,
                "each empty sync increments the streak (it climbs to the warn threshold, not capping early)",
            )
        }

    @Test
    fun `a delivering sync resets the health streak to zero`() =
        runBlocking {
            db.followDao().insert(bioFollow(streak = 2))
            worker(attempt = 0, backends = registryReturning(page(hit(Source.BIORXIV, "10.1101/new")))).doWork()
            assertEquals(0, bioStreak().emptySyncStreak, "any delivery resets the streak")
        }

    @Test
    fun `a redundant sync (papers already in the inbox) still resets the streak — count is pre-IGNORE`() =
        runBlocking {
            db.followDao().insert(bioFollow(streak = 3))
            val followId = bioStreak().id
            val h = hit(Source.BIORXIV, "10.1101/redundant")
            val storageId = ExternalRef(Source.BIORXIV, h.doi).storageId
            // Pre-seed the paper + its inbox row so the re-delivery is a genuine no-op at @Insert IGNORE.
            seed(
                Paper(
                    ref = ExternalRef(Source.BIORXIV, h.doi), latestVersion = 1, title = "t", abstract = "a",
                    publishedAt = Instant.EPOCH, updatedAt = Instant.EPOCH, primaryCategory = "",
                    categories = emptyList(), authors = emptyList(), doi = h.doi, pdfUrl = "",
                ),
            )
            db.inboxDao().insertAll(listOf(InboxItemEntity(paperId = storageId, followId = followId, arrivedAt = 0)))

            worker(attempt = 0, backends = registryReturning(page(h))).doWork()

            assertEquals(0, bioStreak().emptySyncStreak, "a working-but-redundant follow is NOT read as dead")
            assertEquals(1, db.paperDao().count(), "the redundant paper is not duplicated")
        }

    @Test
    fun `a failed fetch does not bump the streak and never advances the cursor`() =
        runBlocking {
            // origin defaults to arxiv → the native Atom path; a 404 is a fetch Failure.
            db.followDao().insert(
                FollowEntity(type = FollowEntity.TYPE_CATEGORY, value = "cs.LG", label = "ML", createdAt = 0),
            )
            server.enqueue(MockResponse().setResponseCode(404))

            assertTrue(worker(attempt = 0).doWork() is ListenableWorker.Result.Retry)

            val row = assertNotNull(db.followDao().find(FollowEntity.TYPE_CATEGORY, "cs.LG", Source.ARXIV.wire))
            assertEquals(0, row.emptySyncStreak, "a fetch failure must never inflate the health streak")
            assertNull(row.lastSyncedAt, "a failure never advances the cursor (so retry refetches)")
        }

    @Test
    fun `a skipped follow (unknown origin) neither retries nor touches the streak`() =
        runBlocking {
            db.followDao().insert(
                FollowEntity(
                    type = FollowEntity.TYPE_CATEGORY,
                    value = "x",
                    label = "x",
                    createdAt = 0,
                    origin = "nonexistent",
                ),
            )

            assertTrue(worker(attempt = 0).doWork() is ListenableWorker.Result.Success)

            val row = assertNotNull(db.followDao().find(FollowEntity.TYPE_CATEGORY, "x", "nonexistent"))
            assertEquals(0, row.emptySyncStreak, "a config-dead follow's streak is frozen (not a quiet-feed signal)")
            assertNull(row.lastSyncedAt, "Skipped freezes the cursor")
        }
}
