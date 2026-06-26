package dev.blokz.arxiver.core.search

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden tests for the PA.5a deterministic layout engine. The whole point of "layout as data" is
 * that legibility is provable off-device: these run on the JVM with no Android, no model, no
 * network. The load-bearing guarantees — *byte-identical output regardless of input order* and *no
 * node-soup* — are asserted structurally so they hold bit-for-bit on Linux CI and a Windows dev box
 * alike (the engine uses only IEEE-deterministic math + StrictMath transcendentals).
 */
class GraphLayoutEngineTest {
    private fun node(
        id: String,
        inLibrary: Boolean = false,
        isCenter: Boolean = false,
    ) = RelationNode(id, "Paper $id", inLibrary = inLibrary, isCenter = isCenter)

    /** A connected, mildly-branched ~50-node graph (tree backbone + a few similarity edges). */
    private fun connected50(): RelationGraph {
        val nodes = (0 until 50).map { node("p%02d".format(it)) }
        val edges = ArrayList<RelationEdge>()
        for (i in 1 until 50) {
            edges.add(RelationEdge("p%02d".format(i / 2), "p%02d".format(i), RelationEdgeKind.CITES))
        }
        for (i in 0 until 49 step 5) {
            edges.add(RelationEdge("p%02d".format(i), "p%02d".format(i + 1), RelationEdgeKind.SIMILAR, 0.80))
        }
        return RelationGraph(nodes, edges)
    }

    /** Two unconnected triangles — used to prove disconnected components are packed apart. */
    private fun twoTriangles(): RelationGraph {
        val nodes = listOf("a", "b", "c", "x", "y", "z").map { node(it) }
        val tri = { p: String, q: String, r: String ->
            listOf(
                RelationEdge(p, q, RelationEdgeKind.SIMILAR, 0.9),
                RelationEdge(q, r, RelationEdgeKind.SIMILAR, 0.9),
                RelationEdge(r, p, RelationEdgeKind.SIMILAR, 0.9),
            )
        }
        return RelationGraph(nodes, tri("a", "b", "c") + tri("x", "y", "z"))
    }

    private fun minPairwiseDistance(scene: GraphScene): Double {
        var min = Double.POSITIVE_INFINITY
        val ns = scene.nodes
        for (i in ns.indices) {
            for (j in i + 1 until ns.size) {
                val dx = ns[i].x - ns[j].x
                val dy = ns[i].y - ns[j].y
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                if (d < min) min = d
            }
        }
        return min
    }

    // --- determinism ---

    @Test
    fun `same graph lays out byte-identically twice`() {
        val g = connected50()
        assertEquals(GraphLayoutEngine.layout(g), GraphLayoutEngine.layout(g))
    }

    @Test
    fun `layout is invariant to input node and edge ordering`() {
        val g = connected50()
        val shuffled =
            RelationGraph(
                nodes = g.nodes.reversed(),
                edges = g.edges.reversed(),
            )
        assertEquals(
            GraphLayoutEngine.layout(g),
            GraphLayoutEngine.layout(shuffled),
            "canonicalisation must make order irrelevant (the seed is sorted rank, not list index)",
        )
    }

    // --- legibility (no node-soup) ---

    @Test
    fun `a 50-node graph spreads legibly — no two nodes collapse together`() {
        val scene = GraphLayoutEngine.layout(connected50())
        assertEquals(50, scene.nodes.size)
        // Repulsion (k^2/d explodes as d -> 0) guarantees strong separation; 10 units is a safe floor
        // well below the 50-unit ideal edge length. A regression that collapses the layout trips this.
        assertTrue(
            minPairwiseDistance(scene) >= 10.0,
            "nodes must stay separated; min distance was ${minPairwiseDistance(scene)}",
        )
        // And the layout must occupy real 2D area (not a line / a point).
        assertTrue(scene.bounds.width > 0.0 && scene.bounds.height > 0.0, "scene has 2D extent")
    }

    @Test
    fun `disconnected components do not overlap`() {
        val scene = GraphLayoutEngine.layout(twoTriangles())
        val byId = scene.nodes.associateBy { it.id }

        fun box(ids: List<String>): SceneBounds {
            val ps = ids.map { byId.getValue(it) }
            return SceneBounds(ps.minOf { it.x }, ps.minOf { it.y }, ps.maxOf { it.x }, ps.maxOf { it.y })
        }
        val a = box(listOf("a", "b", "c"))
        val b = box(listOf("x", "y", "z"))
        // Two axis-aligned boxes are disjoint iff they're separated on at least one axis.
        val separated = a.maxX < b.minX || b.maxX < a.minX || a.maxY < b.minY || b.maxY < a.minY
        assertTrue(separated, "shelf-packing must keep the two triangles' bounding boxes apart: a=$a b=$b")
    }

    // --- structural edge cases ---

    @Test
    fun `empty graph yields an empty scene with zero bounds`() {
        val scene = GraphLayoutEngine.layout(RelationGraph(emptyList(), emptyList()))
        assertTrue(scene.nodes.isEmpty() && scene.edges.isEmpty())
        assertEquals(SceneBounds(0.0, 0.0, 0.0, 0.0), scene.bounds)
    }

    @Test
    fun `a single node sits at the origin`() {
        val scene = GraphLayoutEngine.layout(RelationGraph(listOf(node("solo", isCenter = true)), emptyList()))
        assertEquals(1, scene.nodes.size)
        assertEquals(0.0, scene.nodes[0].x)
        assertEquals(0.0, scene.nodes[0].y)
        assertTrue(scene.nodes[0].isCenter)
    }

    @Test
    fun `duplicate node ids are collapsed to one`() {
        val g = RelationGraph(listOf(node("dup"), node("dup"), node("other")), emptyList())
        assertEquals(2, GraphLayoutEngine.layout(g).nodes.size)
    }

    @Test
    fun `self-loops and edges to unknown nodes are dropped`() {
        val g =
            RelationGraph(
                nodes = listOf(node("a"), node("b")),
                edges =
                    listOf(
                        // self-loop
                        RelationEdge("a", "a", RelationEdgeKind.CITES),
                        // unknown endpoint
                        RelationEdge("a", "ghost", RelationEdgeKind.CITES),
                        // the only valid edge
                        RelationEdge("a", "b", RelationEdgeKind.SIMILAR, 0.7),
                    ),
            )
        val scene = GraphLayoutEngine.layout(g)
        assertEquals(1, scene.edges.size, "only the valid a-b edge survives")
        assertEquals(RelationEdgeKind.SIMILAR, scene.edges[0].kind)
    }

    @Test
    fun `a reversed-direction duplicate of the same kind is deduped, different kinds are kept`() {
        val g =
            RelationGraph(
                nodes = listOf(node("a"), node("b")),
                edges =
                    listOf(
                        RelationEdge("a", "b", RelationEdgeKind.CITES),
                        // same unordered pair + kind → dropped
                        RelationEdge("b", "a", RelationEdgeKind.CITES),
                        // different kind → kept
                        RelationEdge("a", "b", RelationEdgeKind.SIMILAR, 0.6),
                    ),
            )
        val kinds = GraphLayoutEngine.layout(g).edges.map { it.kind }.toSet()
        assertEquals(2, GraphLayoutEngine.layout(g).edges.size)
        assertEquals(setOf(RelationEdgeKind.CITES, RelationEdgeKind.SIMILAR), kinds)
    }

    @Test
    fun `edges reference nodes by sorted index and carry rounded weights`() {
        val g =
            RelationGraph(
                // ids out of order on purpose — the engine must sort them
                nodes = listOf(node("zzz"), node("aaa")),
                edges = listOf(RelationEdge("zzz", "aaa", RelationEdgeKind.SIMILAR, 0.123456)),
            )
        val scene = GraphLayoutEngine.layout(g)
        assertEquals("aaa", scene.nodes[0].id, "nodes are sorted by id")
        assertEquals("zzz", scene.nodes[1].id)
        val e = scene.edges.single()
        assertEquals(setOf(0, 1), setOf(e.from, e.to), "edge endpoints are valid indices")
        assertEquals(0.1235, e.weight, "cosine weight is rounded to 4dp")
    }

    @Test
    fun `degree counts deduped undirected incident edges`() {
        val g =
            RelationGraph(
                nodes = listOf(node("hub"), node("a"), node("b")),
                edges =
                    listOf(
                        RelationEdge("hub", "a", RelationEdgeKind.CITES),
                        RelationEdge("hub", "b", RelationEdgeKind.CITES),
                    ),
            )
        val byId = GraphLayoutEngine.layout(g).nodes.associateBy { it.id }
        assertEquals(2, byId.getValue("hub").degree)
        assertEquals(1, byId.getValue("a").degree)
        assertEquals(1, byId.getValue("b").degree)
    }

    @Test
    fun `the 50-node scene serializes to stable JSON across runs`() {
        // The scene is the artifact; proving it serializes deterministically guards the PA.5b cache
        // + the eventual on-disk/parcel paths. (Printed for human eyeballing of legibility.)
        val json = Json { prettyPrint = true }
        val a = json.encodeToString(GraphLayoutEngine.layout(connected50()))
        val b = json.encodeToString(GraphLayoutEngine.layout(connected50()))
        assertEquals(a, b)
        println("PA.5a 50-node scene JSON (${a.length} chars):\n$a")
    }
}
