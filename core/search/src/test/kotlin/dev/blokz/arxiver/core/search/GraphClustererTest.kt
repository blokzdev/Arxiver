package dev.blokz.arxiver.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** PA.5b community detection: label propagation + the arXiv-category sparse fallback. Pure JVM. */
class GraphClustererTest {
    private fun node(
        id: String,
        category: String? = null,
    ) = RelationNode(id, "Paper $id", primaryCategory = category)

    private fun cites(
        a: String,
        b: String,
    ) = RelationEdge(a, b, RelationEdgeKind.CITES)

    @Test
    fun `two internally-connected groups become two clusters`() {
        // Two triangles, no bridge.
        val nodes = listOf("a", "b", "c", "x", "y", "z").map { node(it) }
        val edges =
            listOf(cites("a", "b"), cites("b", "c"), cites("c", "a"), cites("x", "y"), cites("y", "z"), cites("z", "x"))
        val scene = GraphSceneBuilder.build(RelationGraph(nodes, edges))
        assertEquals(2, scene.clusters.size)
        assertEquals(listOf(3, 3), scene.clusters.map { it.memberIndices.size })
        val byId = scene.nodes.associateBy { it.id }
        assertEquals(byId.getValue("a").clusterId, byId.getValue("c").clusterId)
        assertNotEquals(byId.getValue("a").clusterId, byId.getValue("x").clusterId)
        assertTrue(byId.getValue("a").clusterId >= 0)
    }

    @Test
    fun `a singleton paper gets the unconnected cluster id, not a cluster of its own`() {
        // A triangle + one isolated paper (no edges, no category).
        val nodes = listOf(node("a"), node("b"), node("c"), node("d"))
        val edges = listOf(cites("a", "b"), cites("b", "c"), cites("c", "a"))
        val scene = GraphSceneBuilder.build(RelationGraph(nodes, edges))
        assertEquals(1, scene.clusters.size)
        assertEquals(3, scene.clusters[0].memberIndices.size)
        val byId = scene.nodes.associateBy { it.id }
        assertEquals(GraphScene.UNCONNECTED_GROUP, byId.getValue("d").clusterId)
        assertTrue(byId.getValue("a").clusterId >= 0)
    }

    @Test
    fun `a cluster is labelled with its highest-degree member's title`() {
        // A star: the hub has degree 3, the leaves degree 1 → the whole star is one community.
        val nodes = listOf(node("hub"), node("l1"), node("l2"), node("l3"))
        val edges = listOf(cites("hub", "l1"), cites("hub", "l2"), cites("hub", "l3"))
        val scene = GraphSceneBuilder.build(RelationGraph(nodes, edges))
        assertEquals(1, scene.clusters.size)
        assertEquals(4, scene.clusters[0].memberIndices.size)
        assertEquals("Paper hub", scene.clusters[0].label)
    }

    @Test
    fun `when the graph is too sparse for communities it falls back to category grouping`() {
        // No edges at all → label propagation yields only singletons → category fallback fires.
        val nodes =
            listOf(node("p1", "cs.LG"), node("p2", "cs.LG"), node("p3", "math.CO"), node("p4", "math.CO"))
        val scene = GraphSceneBuilder.build(RelationGraph(nodes, emptyList()))
        assertEquals(2, scene.clusters.size, "two categories → two clusters")
        val byId = scene.nodes.associateBy { it.id }
        assertEquals(byId.getValue("p1").clusterId, byId.getValue("p2").clusterId)
        assertNotEquals(byId.getValue("p1").clusterId, byId.getValue("p3").clusterId)
    }

    @Test
    fun `sparse with no categories leaves every paper unconnected`() {
        val nodes = listOf(node("a"), node("b"), node("c"))
        val scene = GraphSceneBuilder.build(RelationGraph(nodes, emptyList()))
        assertTrue(scene.clusters.isEmpty())
        assertTrue(scene.nodes.all { it.clusterId == GraphScene.UNCONNECTED_GROUP })
    }
}
