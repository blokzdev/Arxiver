package dev.blokz.arxiver.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PA.5b edge-pruning + end-to-end build determinism. Pure JVM. */
class GraphSceneBuilderTest {
    private fun node(id: String) = RelationNode(id, "Paper $id")

    @Test
    fun `citation edges are always kept`() {
        val g = RelationGraph(listOf(node("a"), node("b")), listOf(RelationEdge("a", "b", RelationEdgeKind.CITES)))
        val pruned = GraphSceneBuilder.prune(g)
        assertEquals(1, pruned.edges.size)
        assertEquals(RelationEdgeKind.CITES, pruned.edges[0].kind)
    }

    @Test
    fun `similarity edges below the floor are dropped, at-or-above are kept`() {
        val weak =
            RelationGraph(listOf(node("a"), node("b")), listOf(RelationEdge("a", "b", RelationEdgeKind.SIMILAR, 0.2)))
        assertTrue(GraphSceneBuilder.prune(weak).edges.isEmpty(), "0.2 < 0.3 floor")
        val ok =
            RelationGraph(listOf(node("a"), node("b")), listOf(RelationEdge("a", "b", RelationEdgeKind.SIMILAR, 0.5)))
        assertEquals(1, GraphSceneBuilder.prune(ok).edges.size)
    }

    @Test
    fun `k-NN drops only the edge that is the weakest for both endpoints`() {
        // A 6-clique with all-distinct weights; (n0,n1) is the global minimum, so it is each of n0's
        // and n1's weakest incident edge → dropped by both → removed. Every other edge survives in at
        // least one endpoint's top-4. 15 clique edges − 1 = 14.
        val ids = (0..5).map { "n$it" }
        val nodes = ids.map { node(it) }
        val edges = ArrayList<RelationEdge>()
        for (i in 0..5) {
            for (j in i + 1..5) {
                edges.add(RelationEdge(ids[i], ids[j], RelationEdgeKind.SIMILAR, 0.4 + (i * 6 + j) * 0.005))
            }
        }
        val sim =
            GraphSceneBuilder.prune(
                RelationGraph(nodes, edges),
            ).edges.filter { it.kind == RelationEdgeKind.SIMILAR }
        assertEquals(14, sim.size)
        assertFalse(
            sim.any { (it.from == "n0" && it.to == "n1") || (it.from == "n1" && it.to == "n0") },
            "the globally-weakest mutual edge n0-n1 is pruned",
        )
    }

    @Test
    fun `a degree-1 leaf keeps its only similarity edge (either-endpoint k-NN)`() {
        // A hub with 6 leaves: from the hub's view only 4 survive, but each leaf's single edge is in
        // that leaf's top-4, so the union keeps all 6. Pruning only bites between high-degree nodes.
        val nodes = (0..6).map { node("p$it") }
        val edges = (1..6).map { RelationEdge("p0", "p$it", RelationEdgeKind.SIMILAR, 0.9 - it * 0.05) }
        assertEquals(6, GraphSceneBuilder.prune(RelationGraph(nodes, edges)).edges.size)
    }

    @Test
    fun `build is deterministic and invariant to input order`() {
        val g = communities(5, 12)
        assertEquals(GraphSceneBuilder.build(g), GraphSceneBuilder.build(g))
        val shuffled = RelationGraph(g.nodes.reversed(), g.edges.reversed())
        assertEquals(GraphSceneBuilder.build(g), GraphSceneBuilder.build(shuffled))
    }

    /** [numCommunities] cliques of [perCommunity] (unambiguous communities), no inter-group edges. */
    private fun communities(
        numCommunities: Int,
        perCommunity: Int,
    ): RelationGraph {
        val nodes = ArrayList<RelationNode>()
        val edges = ArrayList<RelationEdge>()
        for (c in 0 until numCommunities) {
            val ids = (0 until perCommunity).map { "c%02dn%02d".format(c, it) }
            ids.forEach { nodes.add(node(it)) }
            for (i in 0 until perCommunity) {
                for (j in i + 1 until perCommunity) {
                    edges.add(RelationEdge(ids[i], ids[j], RelationEdgeKind.CITES))
                }
            }
        }
        return RelationGraph(nodes, edges)
    }
}
