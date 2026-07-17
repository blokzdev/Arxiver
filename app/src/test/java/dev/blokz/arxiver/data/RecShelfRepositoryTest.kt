package dev.blokz.arxiver.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.database.entity.PaperFeedbackEntity
import dev.blokz.arxiver.core.network.s2.S2Author
import dev.blokz.arxiver.core.network.s2.S2ExternalIds
import dev.blokz.arxiver.core.network.s2.S2RecommendationsResponse
import dev.blokz.arxiver.core.network.s2.S2SearchPaper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PRS.2 — the "Recommended for you" repository: the saves∪thumb-ups seed policy (recency blend,
 * non-seedable drop, positives-only STRUCTURALLY), the pre-network NoSeeds guard, the terminal-400
 * mapping, and the shared dedup contract. The transport is a fake lambda (the wire is covered by
 * `SemanticScholarClientTest`); `random` is seeded for a deterministic blend.
 */
@RunWith(RobolectricTestRunner::class)
class RecShelfRepositoryTest {
    private lateinit var db: ArxiverDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
    }

    @After
    fun tearDown() = db.close()

    private fun repo(
        recommend: suspend (List<String>, Int) -> AppResult<S2RecommendationsResponse> = { _, _ -> success() },
    ) = RecShelfRepository(recommend, db.paperDao(), db.libraryDao(), db.paperFeedbackDao())

    private fun success(vararg papers: S2SearchPaper) =
        AppResult.Success(S2RecommendationsResponse(recommendedPapers = papers.toList()))

    private fun storedPaper(
        id: String,
        source: String = "SEARCH",
        doiNorm: String? = null,
    ) = PaperEntity(
        id = id, latestVersion = 1, title = id, abstract = "", publishedAt = 0, updatedAt = 0,
        primaryCategory = "", authorsLine = "", comment = null, journalRef = null, doi = doiNorm,
        pdfUrl = "", citationCount = null, s2PaperId = null, source = source, fetchedAt = 0,
        embeddedAt = null, citationsSyncedAt = null, doiNorm = doiNorm,
    )

    private fun candidate(
        s2Id: String = "r1",
        title: String? = "A recommended paper",
        arxiv: String? = null,
        doi: String? = null,
    ) = S2SearchPaper(
        paperId = s2Id,
        title = title,
        abstract = "An abstract.",
        externalIds = S2ExternalIds(ArXiv = arxiv, DOI = doi),
        year = 2026,
        authors = listOf(S2Author(authorId = "1", name = "C. Lee")),
    )

    private suspend fun save(
        id: String,
        addedAt: Long,
        doiNorm: String? = null,
    ) {
        db.paperDao().upsertPaper(storedPaper(id, doiNorm = doiNorm))
        db.libraryDao().upsertEntry(LibraryEntryEntity(paperId = id, addedAt = addedAt))
    }

    private suspend fun feedback(
        id: String,
        signal: Int,
        source: String,
        createdAt: Long,
        doiNorm: String? = null,
    ) {
        db.paperDao().upsertPaper(storedPaper(id, doiNorm = doiNorm))
        db.paperFeedbackDao().upsert(
            PaperFeedbackEntity(paperId = id, signal = signal, source = source, createdAt = createdAt),
        )
    }

    // --- seed policy ---

    @Test
    fun `seeds blend saves and thumb-ups with prefixed public ids, saves recency-first`() =
        runBlocking {
            save("2606.11111", addedAt = 30)
            save("biorxiv:10.1101/2026.06.01.123456", addedAt = 20, doiNorm = "10.1101/2026.06.01.123456")
            feedback("2606.22222", signal = 1, source = "thumb", createdAt = 10)
            val seeds = repo().seedIds()
            assertEquals(
                listOf("ARXIV:2606.11111", "DOI:10.1101/2026.06.01.123456", "ARXIV:2606.22222"),
                seeds,
            )
        }

    @Test
    fun `dismissed and thumb-down papers contribute NO seed - dismissals stay local`() =
        runBlocking {
            save("2606.11111", addedAt = 30)
            feedback("2606.33333", signal = -1, source = "dismiss", createdAt = 20)
            feedback("2606.44444", signal = -1, source = "thumb", createdAt = 10)
            assertEquals(listOf("ARXIV:2606.11111"), repo().seedIds())
        }

    @Test
    fun `a row with neither a parsing arXiv id nor a doi_norm is dropped as non-seedable`() =
        runBlocking {
            save("psyarxiv:osf-abc", addedAt = 30, doiNorm = null)
            save("2606.11111", addedAt = 20)
            assertEquals(listOf("ARXIV:2606.11111"), repo().seedIds())
        }

    @Test
    fun `papers merely stored on device - neither saved nor thumbed - contribute nothing`() =
        runBlocking {
            db.paperDao().upsertPaper(storedPaper("2606.99999"))
            assertTrue(repo().seedIds().isEmpty())
        }

    @Test
    fun `the blend caps at 20 and always includes the 10 most recent seedables`() =
        runBlocking {
            (1..30).forEach { save("2606.%05d".format(it), addedAt = it.toLong()) }
            val seeds = repo().seedIds()
            assertEquals(RecShelfRepository.RECENT_COUNT + RecShelfRepository.SAMPLE_COUNT, seeds.size)
            val tenMostRecent = (30 downTo 21).map { "ARXIV:2606.%05d".format(it) }
            assertEquals(tenMostRecent, seeds.take(10))
            // The sampled half comes from the OLDER positives only — never duplicates the recent half.
            assertTrue(seeds.drop(10).all { it in (1..20).map { i -> "ARXIV:2606.%05d".format(i) } })
            assertEquals(seeds.size, seeds.distinct().size)
        }

    @Test
    fun `a saved paper that was also thumb-upped yields ONE seed`() =
        runBlocking {
            save("2606.11111", addedAt = 30)
            db.paperFeedbackDao().upsert(
                PaperFeedbackEntity(paperId = "2606.11111", signal = 1, source = "thumb", createdAt = 10),
            )
            assertEquals(listOf("ARXIV:2606.11111"), repo().seedIds())
        }

    @Test
    fun `two rows sharing a doi_norm resolve to ONE seed - the disclosed count never over-states`() =
        runBlocking {
            save("biorxiv:10.1101/2026.06.01.123456", addedAt = 30, doiNorm = "10.1101/2026.06.01.123456")
            save("researchsquare:10.1101/2026.06.01.123456", addedAt = 20, doiNorm = "10.1101/2026.06.01.123456")
            assertEquals(listOf("DOI:10.1101/2026.06.01.123456"), repo().seedIds())
        }

    @Test
    fun `seedIds is IDEMPOTENT past the sampled half - disclose-then-fetch can never drift`() =
        runBlocking {
            // >10 seedables engages the sampled "older" half — the path where a shared mutable RNG
            // would re-roll between the invitation card's disclosure and the tap's fetch.
            (1..30).forEach { save("2606.%05d".format(it), addedAt = it.toLong()) }
            val first = repo().seedIds()
            val second = repo().seedIds()
            assertEquals(first, second)
        }

    // --- the pre-network guard + structural egress pins ---

    @Test
    fun `empty seeds short-circuit to NoSeeds and the transport is NEVER invoked`() =
        runBlocking {
            var calls = 0
            val r =
                repo({ _, _ ->
                    calls++
                    success()
                }).recommend(emptyList())
            assertTrue(r is RecShelfResult.NoSeeds)
            assertEquals(0, calls)
        }

    @Test
    fun `the transport receives EXACTLY the disclosed list - only prefixed public id strings`() =
        runBlocking {
            save("2606.11111", addedAt = 30)
            save("biorxiv:10.1101/2026.06.01.123456", addedAt = 20, doiNorm = "10.1101/2026.06.01.123456")
            feedback("2606.22222", signal = 1, source = "thumb", createdAt = 10)
            var sent: List<String>? = null
            val repo =
                repo({ ids, _ ->
                    sent = ids
                    success()
                })
            val disclosed = repo.seedIds()
            repo.recommend(disclosed)
            // Disclosed == sent, verbatim — the count on the invitation card can never drift from the wire.
            assertEquals(disclosed, sent)
            // Every string is a prefixed PUBLIC identifier; the signal/source/score columns never leave.
            val prefixed = Regex("^(ARXIV|DOI):.+")
            assertTrue(sent!!.isNotEmpty() && sent!!.all { prefixed.matches(it) }, "$sent")
            assertTrue(sent!!.none { it.contains("thumb") || it.contains("dismiss") }, "$sent")
        }

    // --- failure taxonomy ---

    @Test
    fun `a 400 maps to terminal NotRecommendable - never a retryable error`() =
        runBlocking {
            save("psyarxiv:osf-x", addedAt = 1, doiNorm = "10.31234/osf.io/unindexed")
            val repo = repo({ _, _ -> AppResult.Failure(AppError.Upstream(400)) })
            assertTrue(repo.recommend(repo.seedIds()) is RecShelfResult.NotRecommendable)
        }

    @Test
    fun `429 and offline map to a retryable Error - never a false empty`() =
        runBlocking {
            save("2606.11111", addedAt = 1)
            val r429 = repo({ _, _ -> AppResult.Failure(AppError.Upstream(429)) })
            assertTrue(r429.recommend(r429.seedIds()) is RecShelfResult.Error)
            val rOff = repo({ _, _ -> AppResult.Failure(AppError.Offline) })
            assertTrue(rOff.recommend(rOff.seedIds()) is RecShelfResult.Error)
        }

    @Test
    fun `an empty envelope is the none-returned empty`() =
        runBlocking {
            save("2606.11111", addedAt = 1)
            val repo = repo({ _, _ -> success() })
            assertTrue(repo.recommend(repo.seedIds()) is RecShelfResult.EmptyNoneReturned)
        }

    // --- shared dedup contract (the hoisted S2HitMapping) ---

    @Test
    fun `a candidate already on device by arXiv id is deduped to an honest all-local empty`() =
        runBlocking {
            save("2606.11111", addedAt = 1)
            db.paperDao().upsertPaper(storedPaper("2606.55555"))
            val repo = repo({ _, _ -> success(candidate(arxiv = "2606.55555")) })
            assertTrue(repo.recommend(repo.seedIds()) is RecShelfResult.EmptyAllLocal)
        }

    @Test
    fun `a candidate matching a stored row by normalized DOI is deduped cross-source`() =
        runBlocking {
            save("2606.11111", addedAt = 1)
            db.paperDao().upsertPaper(
                storedPaper("biorxiv:10.1101/2026.07.01.654321", doiNorm = "10.1101/2026.07.01.654321"),
            )
            val repo = repo({ _, _ -> success(candidate(doi = "https://doi.org/10.1101/2026.07.01.654321")) })
            assertTrue(repo.recommend(repo.seedIds()) is RecShelfResult.EmptyAllLocal)
        }

    @Test
    fun `a candidate held only as an S2 citation stub still surfaces`() =
        runBlocking {
            save("2606.11111", addedAt = 1)
            db.paperDao().upsertPaper(storedPaper("2606.55555", source = "S2_STUB"))
            val repo = repo({ _, _ -> success(candidate(arxiv = "2606.55555")) })
            val r = repo.recommend(repo.seedIds())
            assertIs<RecShelfResult.Ready>(r)
            assertEquals(1, r.hits.size)
        }

    @Test
    fun `untitled or id-less candidates are dropped and the display cap bounds the shelf`() =
        runBlocking {
            save("2606.11111", addedAt = 1)
            val many =
                (1..15).map { candidate(s2Id = "r$it", arxiv = "2607.%05d".format(it)) } +
                    candidate(s2Id = "no-title", title = null, arxiv = "2608.11111") +
                    S2SearchPaper(paperId = "", title = "no id")
            val repo = repo({ _, _ -> success(*many.toTypedArray()) })
            val r = repo.recommend(repo.seedIds())
            assertIs<RecShelfResult.Ready>(r)
            assertEquals(RecShelfRepository.DISPLAY_CAP, r.hits.size)
            assertTrue(r.hits.none { it.title == "no id" })
        }

    @Test
    fun `request limit exceeds the display cap so dedup survivors can fill it`() {
        assertTrue(RecShelfRepository.REQUEST_LIMIT > RecShelfRepository.DISPLAY_CAP)
    }
}
