package dev.blokz.arxiver.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PA.5b level-of-detail ladder: overview super-nodes, mid/detail labelling, and hit-testing. Pure JVM. */
class GraphSceneLodTest {
    private fun node(id: String) = RelationNode(id, "Paper $id")

    private fun cites(
        a: String,
        b: String,
    ) = RelationEdge(a, b, RelationEdgeKind.CITES)

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

    @Test
    fun `overview collapses a 300-node graph to at most OVERVIEW_MAX super-nodes`() {
        val scene = GraphSceneBuilder.build(communities(20, 15))
        assertEquals(20, scene.clusters.size, "20 cliques → 20 communities")
        val overview = scene.visibleAt(LodTier.OVERVIEW)
        assertTrue(overview.nodes.size <= GraphScene.OVERVIEW_MAX, "overview is bounded: ${overview.nodes.size}")
        // 20 real clusters > 11 kept slots → 11 cluster super-nodes + 1 holding node = 12.
        assertEquals(11, overview.nodes.count { it.kind == RenderKind.CLUSTER })
        assertEquals(1, overview.nodes.count { it.kind == RenderKind.UNCONNECTED })
    }

    @Test
    fun `isolated papers collapse into one Unconnected holding node carrying the count`() {
        val nodes = listOf(node("a"), node("b"), node("c")) + (0 until 5).map { node("iso$it") }
        val scene =
            GraphSceneBuilder.build(
                RelationGraph(nodes, listOf(cites("a", "b"), cites("b", "c"), cites("c", "a"))),
            )
        val overview = scene.visibleAt(LodTier.OVERVIEW)
        assertEquals(1, overview.nodes.count { it.kind == RenderKind.CLUSTER })
        val unconnected = overview.nodes.single { it.kind == RenderKind.UNCONNECTED }
        assertEquals(5, unconnected.count)
    }

    @Test
    fun `inter-cluster edges bundle into one weighted meta-edge per pair`() {
        // Two triangles joined by a single bridge edge → 2 communities, 1 crossing edge.
        val nodes = listOf("a", "b", "c", "x", "y", "z").map { node(it) }
        val edges =
            listOf(
                cites("a", "b"),
                cites("b", "c"),
                cites("c", "a"),
                cites("x", "y"),
                cites("y", "z"),
                cites("z", "x"),
                cites("b", "x"),
            )
        val scene = GraphSceneBuilder.build(RelationGraph(nodes, edges))
        assertEquals(2, scene.clusters.size)
        val overview = scene.visibleAt(LodTier.OVERVIEW)
        assertEquals(2, overview.nodes.count { it.kind == RenderKind.CLUSTER })
        assertEquals(1, overview.edges.size, "the single bridge bundles to one meta-edge")
        assertEquals(1.0, overview.edges[0].weight, "meta-edge weight is the crossing count")
    }

    @Test
    fun `detail labels every paper, mid labels only the top-degree subset`() {
        val scene = GraphSceneBuilder.build(communities(2, 10))
        val detail = scene.visibleAt(LodTier.DETAIL)
        val mid = scene.visibleAt(LodTier.MID)
        assertEquals(20, detail.nodes.size)
        assertEquals(20, mid.nodes.size)
        assertTrue(detail.nodes.all { it.label.isNotEmpty() }, "detail labels all")
        val midLabelled = mid.nodes.count { it.label.isNotEmpty() }
        assertTrue(midLabelled in 1 until 20, "mid labels a strict subset: $midLabelled")
        assertEquals(scene.edges.size, detail.edges.size)
        assertEquals(scene.edges.size, mid.edges.size)
    }

    @Test
    fun `hitTest resolves a paper at detail, a cluster at overview, and nothing in empty space`() {
        val scene = GraphSceneBuilder.build(communities(2, 10))
        val n0 = scene.nodes[0]
        val paperHit = scene.hitTest(n0.x, n0.y, LodTier.DETAIL)
        assertNotNull(paperHit)
        assertEquals(RenderKind.PAPER, paperHit.kind)
        assertEquals(0, paperHit.refId)

        assertNull(scene.hitTest(scene.bounds.maxX + 10_000.0, scene.bounds.maxY + 10_000.0, LodTier.DETAIL))

        val clusterRender = scene.visibleAt(LodTier.OVERVIEW).nodes.first { it.kind == RenderKind.CLUSTER }
        val clusterHit = scene.hitTest(clusterRender.x, clusterRender.y, LodTier.OVERVIEW)
        assertNotNull(clusterHit)
        assertEquals(RenderKind.CLUSTER, clusterHit.kind)
    }

    @Test
    fun `an all-singleton graph shows a single Unconnected node and no meta-edges`() {
        val scene = GraphSceneBuilder.build(RelationGraph(listOf(node("a"), node("b"), node("c")), emptyList()))
        val overview = scene.visibleAt(LodTier.OVERVIEW)
        assertEquals(1, overview.nodes.size)
        assertEquals(RenderKind.UNCONNECTED, overview.nodes[0].kind)
        assertEquals(3, overview.nodes[0].count)
        assertTrue(overview.edges.isEmpty())
    }
}
