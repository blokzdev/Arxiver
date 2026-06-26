package dev.blokz.arxiver.core.search

/**
 * The public "make me a knowledge map" entry point (P-Atlas PA.5): prune → layout → cluster, all
 * pure + deterministic. The renderer (PA.5c) calls [build] off the main thread and then only blits
 * the returned [GraphScene] + asks it for [GraphScene.visibleAt] per zoom tier.
 *
 * **Pruning matters at scale.** Citation edges are already sparse (kept whole), but similarity is
 * dense — every paper has *some* cosine to every other. Left unbounded the map goes O(n²)-hairy, so
 * we keep only edges at/above [SIMILARITY_FLOOR] and, per node, its [SIMILAR_K] strongest similarity
 * links (an edge survives if it is in *either* endpoint's top-K). That bounds edge count to ~k·n and
 * is what keeps both the layout cost and the visual density in check below the PA.5 node cap.
 */
object GraphSceneBuilder {
    /** Drop similarity edges weaker than this cosine (matches the PA.1 feeder floor). */
    const val SIMILARITY_FLOOR = 0.3

    /** Keep at most this many strongest similarity edges incident to each node. */
    const val SIMILAR_K = 4

    fun build(graph: RelationGraph): GraphScene = GraphClusterer.cluster(GraphLayoutEngine.layout(prune(graph)))

    /** Keep all citations; keep each node's top-[SIMILAR_K] above-[SIMILARITY_FLOOR] similarity edges. */
    internal fun prune(graph: RelationGraph): RelationGraph {
        val cites = graph.edges.filter { it.kind == RelationEdgeKind.CITES }
        val similar =
            graph.edges.filter { it.kind == RelationEdgeKind.SIMILAR && (it.weight ?: 0.0) >= SIMILARITY_FLOOR }
        if (similar.isEmpty()) return graph.copy(edges = cites)

        // Incident similarity edges per node, by index into [similar].
        val incidence = HashMap<String, MutableList<Int>>()
        similar.forEachIndexed { idx, e ->
            incidence.getOrPut(e.from) { ArrayList() }.add(idx)
            incidence.getOrPut(e.to) { ArrayList() }.add(idx)
        }
        val keep = HashSet<Int>()
        for ((nodeId, idxs) in incidence) {
            idxs.sortedWith(
                compareByDescending<Int> { similar[it].weight ?: 0.0 }
                    .thenBy {
                        val e = similar[it]
                        if (e.from == nodeId) e.to else e.from
                    },
            ).take(SIMILAR_K).forEach { keep.add(it) }
        }
        val keptSimilar = keep.sorted().map { similar[it] }
        return graph.copy(edges = cites + keptSimilar)
    }
}
