package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.dao.CitationDao
import dev.blokz.arxiver.core.database.dao.EmbeddingDao
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity
import dev.blokz.arxiver.core.search.RelationEdge
import dev.blokz.arxiver.core.search.RelationEdgeKind
import dev.blokz.arxiver.core.search.RelationGraph
import dev.blokz.arxiver.core.search.RelationNode
import dev.blokz.arxiver.core.search.VectorIndex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Narrow seam over [RelationGraphRepository] so [dev.blokz.arxiver.feature.paper.ask.AskViewModel]
 *  stays DAO-free and unit-testable (mirrors the `ScopeIndexer` pattern). */
fun interface RelationGraphSource {
    suspend fun graphForPaper(paperId: String): GraphResult
}

/** Outcome of building a relation graph for a paper (P-Atlas PA.1) — a graph, or a typed empty cause. */
sealed interface GraphResult {
    data class Ready(val graph: RelationGraph) : GraphResult

    /** The center paper has no embedding yet, so semantic neighbors can't be computed. */
    data object NotEmbedded : GraphResult

    /** Embedded, but no neighbors or citation edges are available locally yet. */
    data object NoRelations : GraphResult
}

/**
 * Feeds the pure [dev.blokz.arxiver.core.search.RelationGraphBuilder] (P-Atlas PA.1) from data the
 * app already owns — **network-free**, on-device only. Reuses the Phase-4.9 relations primitives:
 * semantic neighbors from the precomputed `related_papers` (fast path) or a live [VectorIndex.topK]
 * scan (works for any embedded paper, not just worker-processed library papers), plus local citation
 * edges. The graph never leaves the device and needs no AI provider.
 */
class RelationGraphRepository
    @Inject
    constructor(
        private val paperDao: PaperDao,
        private val embeddingDao: EmbeddingDao,
        private val citationDao: CitationDao,
        private val dispatchers: DispatcherProvider,
    ) : RelationGraphSource {
        private val vectorIndex = VectorIndex(embeddingDao)

        override suspend fun graphForPaper(paperId: String): GraphResult =
            withContext(dispatchers.io) {
                val center = paperDao.paperById(paperId) ?: return@withContext GraphResult.NoRelations
                val embedding = embeddingDao.byPaperId(paperId)

                val neighborNodes = mutableListOf<RelationNode>()
                val edges = mutableListOf<RelationEdge>()

                // Semantic neighbors: precomputed first (bundles title + library membership in one
                // query), else a live top-K cosine scan so a freshly-embedded paper still maps.
                val precomputed = embeddingDao.neighborsFor(paperId, MAX_NEIGHBORS)
                if (precomputed.isNotEmpty()) {
                    precomputed.forEach {
                        neighborNodes += RelationNode(it.paper.id, it.paper.title, inLibrary = it.in_library)
                        edges += RelationEdge(paperId, it.paper.id, RelationEdgeKind.SIMILAR, it.similarity)
                    }
                } else if (embedding != null) {
                    val vector = PaperEmbeddingEntity.blobToFloats(embedding.vector)
                    vectorIndex.topK(vector, MAX_NEIGHBORS, excludeId = paperId)
                        .filter { it.similarity >= SIMILARITY_FLOOR }
                        .forEach { hit ->
                            val p = paperDao.paperById(hit.paperId) ?: return@forEach
                            neighborNodes += RelationNode(p.id, p.title, inLibrary = false)
                            edges += RelationEdge(paperId, p.id, RelationEdgeKind.SIMILAR, hit.similarity)
                        }
                }

                // Local citation edges (need a prior CitationSyncWorker run / Semantic Scholar key).
                val citationNodes = mutableListOf<RelationNode>()
                citationDao.observeReferences(paperId).first().forEach {
                    citationNodes += RelationNode(it.paper.id, it.paper.title, inLibrary = it.in_library)
                    edges += RelationEdge(paperId, it.paper.id, RelationEdgeKind.CITES)
                }
                citationDao.observeCitations(paperId).first().forEach {
                    citationNodes += RelationNode(it.paper.id, it.paper.title, inLibrary = it.in_library)
                    edges += RelationEdge(it.paper.id, paperId, RelationEdgeKind.CITES)
                }

                if (neighborNodes.isEmpty() && citationNodes.isEmpty()) {
                    return@withContext if (embedding == null) GraphResult.NotEmbedded else GraphResult.NoRelations
                }
                val nodes =
                    (listOf(RelationNode(center.id, center.title, isCenter = true)) + neighborNodes + citationNodes)
                        .distinctBy { it.id }
                GraphResult.Ready(RelationGraph(nodes, edges))
            }

        companion object {
            private const val MAX_NEIGHBORS = 6
            private const val SIMILARITY_FLOOR = 0.3
        }
    }
