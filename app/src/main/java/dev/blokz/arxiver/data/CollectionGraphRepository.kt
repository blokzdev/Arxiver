package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.dao.CitationDao
import dev.blokz.arxiver.core.database.dao.EmbeddingDao
import dev.blokz.arxiver.core.database.dao.LibraryDao
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.search.GraphScene
import dev.blokz.arxiver.core.search.GraphSceneBuilder
import dev.blokz.arxiver.core.search.RelationEdge
import dev.blokz.arxiver.core.search.RelationEdgeKind
import dev.blokz.arxiver.core.search.RelationGraph
import dev.blokz.arxiver.core.search.RelationNode
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Narrow seam so the knowledge-map ViewModel stays DAO-free + unit-testable (mirrors [RelationGraphSource]). */
fun interface CollectionGraphSource {
    suspend fun sceneForCollection(collectionId: Long): CollectionGraphResult
}

/** Outcome of building a collection knowledge map (P-Atlas PA.5) — a laid-out scene, or a typed empty cause. */
sealed interface CollectionGraphResult {
    data class Ready(val scene: GraphScene) : CollectionGraphResult

    /** The collection has no member papers. */
    data object NoPapers : CollectionGraphResult

    /** Papers exist but no citation/similarity edges among them yet — nothing to map. */
    data object NoRelations : CollectionGraphResult
}

/**
 * Feeds the pure [GraphSceneBuilder] (P-Atlas PA.5) from data the app already owns — **network-free**,
 * on-device only, no AI provider. Citation edges among the collection's members come from
 * [CitationDao.edgesAmong]; similarity edges are each member's precomputed neighbours filtered to
 * in-collection. Above [MAX_NODES] the graph is reduced to the most-central papers (by incident-edge
 * degree) so layout cost + on-screen density stay bounded — the user-approved hard cap. The resulting
 * [GraphScene] never leaves the device. Mirrors [RelationGraphRepository] (the per-paper PA.1 feeder).
 */
class CollectionGraphRepository
    @Inject
    constructor(
        private val libraryDao: LibraryDao,
        private val paperDao: PaperDao,
        private val embeddingDao: EmbeddingDao,
        private val citationDao: CitationDao,
        private val dispatchers: DispatcherProvider,
    ) : CollectionGraphSource {
        override suspend fun sceneForCollection(collectionId: Long): CollectionGraphResult =
            withContext(dispatchers.io) {
                val allIds = libraryDao.paperIdsForCollection(collectionId)
                if (allIds.isEmpty()) return@withContext CollectionGraphResult.NoPapers

                // All edges among the full member set first (so centrality is measured before capping).
                val idSet = allIds.toHashSet()
                val edges = ArrayList<RelationEdge>()
                citationDao.edgesAmong(allIds).forEach {
                    edges += RelationEdge(it.citingId, it.citedId, RelationEdgeKind.CITES)
                }
                for (id in allIds) {
                    embeddingDao.neighborsFor(id, MAX_NEIGHBORS).forEach { n ->
                        if (n.paper.id != id && n.paper.id in idSet) {
                            edges += RelationEdge(id, n.paper.id, RelationEdgeKind.SIMILAR, n.similarity)
                        }
                    }
                }
                if (edges.isEmpty()) return@withContext CollectionGraphResult.NoRelations

                // Hard cap by centrality so a huge collection can't blow up layout or go node-soup.
                val keptIds = capByCentrality(allIds, edges, MAX_NODES)
                val keptSet = keptIds.toHashSet()
                val keptEdges = edges.filter { it.from in keptSet && it.to in keptSet }

                val nodes =
                    keptIds.mapNotNull { id ->
                        val p = paperDao.paperById(id) ?: return@mapNotNull null
                        RelationNode(p.id, p.title, inLibrary = true, primaryCategory = p.primaryCategory)
                    }
                val scene = GraphSceneBuilder.build(RelationGraph(nodes, keptEdges))
                CollectionGraphResult.Ready(scene)
            }

        companion object {
            /** Above this, keep only the most-central papers (the user-approved ~400 hard cap). */
            const val MAX_NODES = 400
            private const val MAX_NEIGHBORS = 6

            /** Keep the [limit] most-central papers (most incident edges); ties by id → deterministic. */
            internal fun capByCentrality(
                ids: List<String>,
                edges: List<RelationEdge>,
                limit: Int,
            ): List<String> {
                if (ids.size <= limit) return ids
                val degree = HashMap<String, Int>()
                for (e in edges) {
                    degree[e.from] = (degree[e.from] ?: 0) + 1
                    degree[e.to] = (degree[e.to] ?: 0) + 1
                }
                return ids.sortedWith(compareByDescending<String> { degree[it] ?: 0 }.thenBy { it }).take(limit)
            }
        }
    }
