package dev.blokz.arxiver.core.search

import dev.blokz.arxiver.core.database.dao.ChunkEmbeddingDao
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity
import dev.blokz.arxiver.core.database.fts.Bm25
import dev.blokz.arxiver.core.database.fts.buildMatchQuery

/** What a RAG chat is grounded in: one paper, or a collection's papers (a KB). */
sealed interface RetrievalScope {
    data class Paper(val paperId: String) : RetrievalScope

    data class Collection(val collectionId: Long) : RetrievalScope
}

/** A retrieved chunk with its fused relevance score and per-leg provenance. */
data class RetrievedChunk(
    val chunkId: Long,
    val paperId: String,
    val text: String,
    val score: Double,
    val provenance: Provenance,
)

/** A scoped chunk with its decoded embedding (semantic leg input). */
data class ScopedChunk(
    val chunkId: Long,
    val paperId: String,
    val text: String,
    val vector: FloatArray,
)

/** Paginated source of a scope's chunk vectors — a seam so the retriever is pure-testable. */
interface ChunkVectorSource {
    suspend fun chunks(
        scope: RetrievalScope,
        limit: Int,
        offset: Int,
    ): List<ScopedChunk>
}

/** Scoped keyword (FTS/BM25) source over chunk text — likewise a testable seam. */
interface ChunkKeywordSource {
    /** @return chunkId → BM25 score for chunks in [scope] matching [query]. */
    suspend fun match(
        query: String,
        scope: RetrievalScope,
        limit: Int,
    ): List<Pair<Long, Double>>
}

/**
 * On-device hybrid chunk retrieval for RAG (SPEC-SEARCH §8). Mirrors the
 * paper-level local search (`SearchViewModel.runLocalSearch`): a semantic leg
 * (cosine over scoped chunk vectors) and a keyword leg (chunk FTS BM25) fused by
 * the shared [HybridFusion]. Scoring keys on chunk id; everything stays on-device.
 * The caller supplies the embedded query (or null to degrade to keyword-only when
 * the model isn't ready).
 */
class RagRetriever(
    private val vectorSource: ChunkVectorSource,
    private val keywordSource: ChunkKeywordSource,
    private val tuning: SearchTuning = SearchTuning(),
) {
    suspend fun retrieve(
        queryVector: FloatArray?,
        query: String,
        scope: RetrievalScope,
        k: Int = tuning.resultLimit,
    ): List<RetrievedChunk> {
        val lookup = HashMap<Long, ScopedChunk>()
        val semantic = mutableListOf<Pair<String, Double>>()

        var offset = 0
        while (true) {
            val batch = vectorSource.chunks(scope, limit = SCAN_PAGE, offset = offset)
            if (batch.isEmpty()) break
            for (c in batch) {
                lookup[c.chunkId] = c
                if (queryVector != null && c.vector.size == queryVector.size) {
                    semantic += c.chunkId.toString() to dotSimilarity(queryVector, c.vector)
                }
            }
            offset += batch.size
        }
        if (lookup.isEmpty()) return emptyList()

        val semanticLeg = semantic.sortedByDescending { it.second }.take(tuning.legLimit)
        val keywordLeg =
            keywordSource.match(query, scope, tuning.legLimit).map { (id, score) -> id.toString() to score }

        return HybridFusion.fuse(keyword = keywordLeg, semantic = semanticLeg, tuning = tuning)
            .take(k)
            .mapNotNull { hit ->
                val chunk = hit.paperId.toLongOrNull()?.let(lookup::get) ?: return@mapNotNull null
                RetrievedChunk(chunk.chunkId, chunk.paperId, chunk.text, hit.score, hit.provenance)
            }
    }

    companion object {
        /** Page size for the scoped cosine scan (matches [VectorIndex]). */
        private const val SCAN_PAGE = 512
    }
}

/** [ChunkVectorSource] backed by [ChunkEmbeddingDao] (scoped, paginated vector reads). */
class DaoChunkVectorSource(private val dao: ChunkEmbeddingDao) : ChunkVectorSource {
    override suspend fun chunks(
        scope: RetrievalScope,
        limit: Int,
        offset: Int,
    ): List<ScopedChunk> {
        val rows =
            when (scope) {
                is RetrievalScope.Paper -> dao.chunksForPaper(scope.paperId, limit, offset)
                is RetrievalScope.Collection -> dao.chunksForCollection(scope.collectionId, limit, offset)
            }
        return rows.map {
            ScopedChunk(it.id, it.paperId, it.chunkText, PaperEmbeddingEntity.blobToFloats(it.vector))
        }
    }
}

/** [ChunkKeywordSource] backed by the chunk FTS index + [Bm25]. */
class DaoChunkKeywordSource(private val dao: ChunkEmbeddingDao) : ChunkKeywordSource {
    override suspend fun match(
        query: String,
        scope: RetrievalScope,
        limit: Int,
    ): List<Pair<Long, Double>> {
        val match = buildMatchQuery(query)
        if (match.isBlank()) return emptyList()
        val hits =
            when (scope) {
                is RetrievalScope.Paper -> dao.matchChunksForPaper(match, scope.paperId, limit)
                is RetrievalScope.Collection -> dao.matchChunksForCollection(match, scope.collectionId, limit)
            }
        return hits.map { it.chunkId to Bm25.score(it.matchinfo, Bm25.CHUNK_WEIGHTS) }
    }
}
