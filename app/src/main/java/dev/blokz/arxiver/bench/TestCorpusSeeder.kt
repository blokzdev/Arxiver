package dev.blokz.arxiver.bench

import dev.blokz.arxiver.core.database.dao.EmbeddingDao
import dev.blokz.arxiver.core.database.dao.FollowDao
import dev.blokz.arxiver.core.database.dao.InboxDao
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.database.entity.FollowEntity
import dev.blokz.arxiver.core.database.entity.InboxItemEntity
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.model.PaperSource
import dev.blokz.arxiver.core.search.SeededCorpus

/**
 * Benchmark-only synthetic corpus loader (P-Prove PP.3). Idempotently seeds N papers + their PP.1 [SeededCorpus]
 * embeddings + one enabled follow + N inbox rows, so the PP.3 Macrobenchmark suites measure a real Today feed and a
 * populated vector index — not `EmptyState`.
 *
 * **Red line:** the ONLY caller is the `BuildConfig.ENABLE_TEST_CORPUS`-gated hook in `ArxiverApplication`, and that
 * flag is `true` only on the ephemeral, non-debuggable `benchmarkRelease`/`nonMinifiedRelease` variants — never
 * `release` or `debug`. The whole seed is ONE [transaction] (single commit; no per-row fsync storm), and every row is
 * visibly synthetic (`p%06d` ids, "Seeded Paper …" titles) — no tokens or PII.
 */
class TestCorpusSeeder(
    private val paperDao: PaperDao,
    private val embeddingDao: EmbeddingDao,
    private val followDao: FollowDao,
    private val inboxDao: InboxDao,
    // Mirrors ChatRepository's provider (AppModule): { block -> db.withTransaction { block() } }. A unit test passes
    // a pass-through { block -> block() } so the seeder is exercisable with fake DAOs and no Room.
    private val transaction: suspend (suspend () -> Unit) -> Unit,
) {
    suspend fun seed(n: Int = CORPUS_SIZE) {
        // Sentinel idempotency (load-bearing: BaselineProfileRule.collect and repeated benchmark iterations re-launch
        // the app): if p000000's embedding is present the corpus is already seeded — do nothing.
        if (embeddingDao.byPaperId(SENTINEL_ID) != null) return

        val embeddings = SeededCorpus.embeddings(n) // ids "p000000".. byte-identical to paperEntity() below
        transaction {
            // 1) parent follow (FK target for inbox + the "has any follows" signal). insert is IGNORE-on-conflict:
            //    -1L means it already existed, so resolve the id via find().
            val followId =
                followDao.insert(SEED_FOLLOW).takeIf { it != -1L }
                    ?: requireNotNull(
                        followDao.find(SEED_FOLLOW.type, SEED_FOLLOW.value, SEED_FOLLOW.origin),
                    ) { "seed follow neither inserted nor found" }.id
            // 2) papers — parent rows for embeddings + inbox (FK order matters).
            for (i in 0 until n) paperDao.upsertPaper(paperEntity(i))
            // 3) embeddings — one upsert per row, but INSIDE the single transaction (GAP-C: no bulk DAO needed; the
            //    wrapping transaction, not a bulk statement, is what collapses N fsyncs into one commit).
            for (embedding in embeddings) embeddingDao.upsert(embedding)
            // 4) inbox rows in bulk. A deterministic descending score ramp (0.95→0.05) spans the 0.55 relevance cut,
            //    so BOTH the "likely relevant" and the below-cut Today sections render (real partition/header path).
            inboxDao.insertAll(
                (0 until n).map { i ->
                    InboxItemEntity(
                        paperId = paperId(i),
                        followId = followId,
                        arrivedAt = BASE_EPOCH - i * 1_000L,
                        state = InboxItemEntity.STATE_NEW,
                        score = 0.95 - (i.toDouble() / n) * 0.90,
                    )
                },
            )
        }
    }

    // title carries the "Seeded Paper" content-ready anchor (By.textContains); abstract is non-empty for FTS/segment
    // realism; source="FOLLOW" (never 'S2_STUB', which the feed/embedder exclude); embeddedAt non-null so the
    // EmbeddingWorker won't try to re-embed these synthetic rows.
    private fun paperEntity(i: Int) =
        PaperEntity(
            id = paperId(i),
            latestVersion = 1,
            title = "Seeded Paper %06d".format(i),
            abstract = "Deterministic synthetic abstract for seeded paper $i.",
            publishedAt = BASE_EPOCH - i * 1_000L,
            updatedAt = BASE_EPOCH - i * 1_000L,
            primaryCategory = "cs.LG",
            authorsLine = "Seeded Author",
            comment = null,
            journalRef = null,
            doi = null,
            pdfUrl = "",
            citationCount = null,
            s2PaperId = null,
            source = PaperSource.FOLLOW.name,
            fetchedAt = BASE_EPOCH,
            embeddedAt = BASE_EPOCH,
            citationsSyncedAt = null,
        )

    private fun paperId(i: Int) = "p%06d".format(i)

    companion object {
        /** Fast to seed, comfortably fills the Today feed for scroll benchmarks; 20K is reserved for a stress pass. */
        const val CORPUS_SIZE = 5_000
        private const val SENTINEL_ID = "p000000"
        private const val BASE_EPOCH = 1_700_000_000_000L
        private val SEED_FOLLOW =
            FollowEntity(
                type = FollowEntity.TYPE_CATEGORY,
                value = "cs.LG",
                label = "Seeded (cs.LG)",
                createdAt = BASE_EPOCH,
                enabled = true,
                origin = "arxiv",
            )
    }
}
