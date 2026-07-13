package dev.blokz.arxiver.core.search

import dev.blokz.arxiver.core.database.dao.ChunkEmbeddingDao
import dev.blokz.arxiver.core.database.fts.Bm25
import dev.blokz.arxiver.core.database.fts.buildMatchQuery

/** Corpus-wide body-chunk keyword source (P-FullText PFT.3): query → (paperId, chunk BM25) per matching body chunk. */
interface CorpusBodyKeywordSource {
    suspend fun matchBody(
        query: String,
        limit: Int,
    ): List<Pair<String, Double>>
}

/**
 * The "Also found in full text" leg (P-FullText PFT.3): rolls corpus-wide body-chunk BM25 matches up to the
 * paper level by **MAX** (never SUM — many weak body mentions must not outrank one strong hit), returning
 * papers ranked best-body-match-first, capped. Pure + unit-tested. Deliberately kept OUT of the paper-level
 * `HybridFusion` and OFF the traced main-search path, so it neither floods the main results nor touches the
 * D2 latency budget; the caller surfaces the result as a distinct, honest "Also found in full text" section.
 */
class CorpusBodyRetriever(
    private val source: CorpusBodyKeywordSource,
    private val matchLimit: Int = DEFAULT_MATCH_LIMIT,
) {
    /** @return up to [k] paperIds (best body BM25 first) whose body text matches [query]; empty on blank/no match. */
    suspend fun retrieve(
        query: String,
        k: Int = DEFAULT_RESULT_LIMIT,
    ): List<String> {
        val best = HashMap<String, Double>()
        for ((paperId, score) in source.matchBody(query, matchLimit)) {
            val prev = best[paperId]
            if (prev == null || score > prev) best[paperId] = score
        }
        return best.entries.sortedByDescending { it.value }.take(k).map { it.key }
    }

    companion object {
        /** DoS backstop on the unscoped body MATCH, NOT relevance truncation — v1's reader-opened subset is small. */
        const val DEFAULT_MATCH_LIMIT = 2000
        const val DEFAULT_RESULT_LIMIT = 20
    }
}

/** [CorpusBodyKeywordSource] backed by the shared `chunk_fts` body leg + [Bm25]. */
class DaoCorpusBodySource(private val dao: ChunkEmbeddingDao) : CorpusBodyKeywordSource {
    override suspend fun matchBody(
        query: String,
        limit: Int,
    ): List<Pair<String, Double>> {
        val match = buildMatchQuery(query)
        if (match.isBlank()) return emptyList()
        return dao.matchBodyChunks(match, limit).map { it.paperId to Bm25.score(it.matchinfo, Bm25.CHUNK_WEIGHTS) }
    }
}
