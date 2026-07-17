package dev.blokz.arxiver.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.ExternalRef
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.Source
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PDM.2 — the "Discover more like this" repository: honest typed states, dedup-vs-corpus keyed exactly
 * like persistence (arXiv-parse-first, then normalized DOI), the S2_STUB boundary, and the display cap.
 * The transport is a fake lambda (the wire itself is covered by `SemanticScholarClientTest`).
 */
@RunWith(RobolectricTestRunner::class)
class DiscoverSimilarRepositoryTest {
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

    private fun repo(recommend: suspend (String, Int) -> AppResult<S2RecommendationsResponse>) =
        DiscoverSimilarRepository(recommend, db.paperDao())

    private fun success(vararg papers: S2SearchPaper) =
        AppResult.Success(S2RecommendationsResponse(recommendedPapers = papers.toList()))

    private fun arxivSeed() =
        Paper(
            ref = ArxivRef(ArxivId("2606.27302")),
            latestVersion = 1,
            title = "Seed",
            abstract = "",
            publishedAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            primaryCategory = "cs.LG",
            categories = emptyList(),
            authors = emptyList(),
        )

    private fun candidate(
        s2Id: String = "s2-$1",
        title: String? = "A similar paper",
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

    @Test
    fun `hits keep S2 order with parsed identities`() =
        runBlocking {
            val r =
                repo({ _, _ ->
                    success(
                        candidate(s2Id = "r1", arxiv = "2606.11111"),
                        candidate(s2Id = "r2", doi = "https://doi.org/10.1101/2026.06.01.123456"),
                    )
                }).discoverSimilar(arxivSeed())
            assertIs<DiscoverResult.Ready>(r)
            assertEquals(listOf("r1", "r2"), r.hits.map { it.s2PaperId })
            assertEquals("2606.11111", r.hits[0].arxivId?.value)
            // The resolver prefix is stripped and the DOI lowercased by normalizeDoi at map time.
            assertEquals("10.1101/2026.06.01.123456", r.hits[1].doi)
            assertNull(r.hits[1].arxivId)
        }

    @Test
    fun `a candidate already on device by arXiv id is deduped to an honest all-local empty`() =
        runBlocking {
            db.paperDao().upsertPaper(storedPaper("2606.11111"))
            val r =
                repo({ _, _ -> success(candidate(arxiv = "2606.11111")) })
                    .discoverSimilar(arxivSeed())
            assertTrue(r is DiscoverResult.EmptyAllLocal)
        }

    @Test
    fun `a candidate held only as an S2 citation stub still surfaces`() =
        runBlocking {
            db.paperDao().upsertPaper(storedPaper("2606.11111", source = "S2_STUB"))
            val r =
                repo({ _, _ -> success(candidate(arxiv = "2606.11111")) })
                    .discoverSimilar(arxivSeed())
            assertIs<DiscoverResult.Ready>(r)
            assertEquals(1, r.hits.size)
        }

    @Test
    fun `a candidate matching a stored row by normalized DOI is deduped cross-source`() =
        runBlocking {
            db.paperDao().upsertPaper(
                storedPaper("biorxiv:10.1101/2026.06.01.123456", doiNorm = "10.1101/2026.06.01.123456"),
            )
            // Mixed case + resolver prefix on the wire — normalizeDoi must key it onto the stored row.
            val r =
                repo({ _, _ -> success(candidate(doi = "https://doi.org/10.1101/2026.06.01.123456")) })
                    .discoverSimilar(arxivSeed())
            assertTrue(r is DiscoverResult.EmptyAllLocal)
        }

    @Test
    fun `untitled or id-less candidates are dropped, not rendered blank`() =
        runBlocking {
            val r =
                repo({ _, _ ->
                    success(
                        candidate(s2Id = "ok", arxiv = "2606.22222"),
                        candidate(title = null, arxiv = "2606.33333"),
                        S2SearchPaper(paperId = "", title = "no id"),
                    )
                }).discoverSimilar(arxivSeed())
            assertIs<DiscoverResult.Ready>(r)
            assertEquals(listOf("ok"), r.hits.map { it.s2PaperId })
        }

    @Test
    fun `the display cap bounds the list`() =
        runBlocking {
            val many =
                (1..30).map { candidate(s2Id = "r$it", arxiv = "2606.5$it") }.toTypedArray()
            val r = repo({ _, _ -> success(*many) }).discoverSimilar(arxivSeed())
            assertIs<DiscoverResult.Ready>(r)
            assertEquals(DiscoverSimilarRepository.DISPLAY_CAP, r.hits.size)
        }

    @Test
    fun `a 404 maps to SeedNotFound - distinct from a retryable error`() =
        runBlocking {
            val r =
                repo({ _, _ -> AppResult.Failure(AppError.Upstream(404)) })
                    .discoverSimilar(arxivSeed())
            assertTrue(r is DiscoverResult.SeedNotFound)
        }

    @Test
    fun `429 and offline map to a retryable Error - never a false empty`() =
        runBlocking {
            assertTrue(
                repo({ _, _ -> AppResult.Failure(AppError.Upstream(429)) })
                    .discoverSimilar(arxivSeed()) is DiscoverResult.Error,
            )
            assertTrue(
                repo({ _, _ -> AppResult.Failure(AppError.Offline) })
                    .discoverSimilar(arxivSeed()) is DiscoverResult.Error,
            )
        }

    @Test
    fun `an empty envelope is the none-returned empty`() =
        runBlocking {
            val r = repo({ _, _ -> success() }).discoverSimilar(arxivSeed())
            assertTrue(r is DiscoverResult.EmptyNoneReturned)
        }

    @Test
    fun `seedIdFor prefers the arXiv id, falls back to the normalized DOI, else null`() {
        val repo = repo({ _, _ -> success() })
        assertEquals("ARXIV:2606.27302", repo.seedIdFor(arxivSeed()))
        val doiSeed =
            arxivSeed().copy(
                ref = ExternalRef(Source.RESEARCH_SQUARE, "10.21203/rs.3.rs-1"),
                doi = "https://doi.org/10.21203/RS.3.RS-1",
            )
        assertEquals("DOI:10.21203/rs.3.rs-1", repo.seedIdFor(doiSeed))
        val bare = arxivSeed().copy(ref = ExternalRef(Source.PSYARXIV, "osf-abc"), doi = null)
        assertNull(repo.seedIdFor(bare))
    }

    @Test
    fun `the seed id is passed to the transport verbatim`() =
        runBlocking {
            var seen: String? = null
            repo({ id, _ ->
                seen = id
                success()
            }).discoverSimilar(arxivSeed())
            assertEquals("ARXIV:2606.27302", seen)
        }

    @Test
    fun `request limit exceeds the display cap so dedup survivors can fill it`() {
        assertTrue(DiscoverSimilarRepository.REQUEST_LIMIT > DiscoverSimilarRepository.DISPLAY_CAP)
    }
}
