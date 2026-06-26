package dev.blokz.arxiver.core.search

import kotlinx.serialization.Serializable

/**
 * A laid-out, render-ready relation graph (P-Atlas PA.5) — the "layout as data" artifact. Where
 * [RelationGraphBuilder] emits Mermaid text for the single-paper static star (PA.1), the
 * collection-scale knowledge map computes its *entire* geometry deterministically in this pure
 * `:core` module ([GraphLayoutEngine] for positions, [GraphSceneBuilder] for the full scene) and
 * hands the dumb on-screen renderer a [GraphScene] it only has to blit + pan/zoom. Because the scene
 * is pure data (and `@Serializable`), legibility is a JVM golden you can eyeball before any device
 * boots, and the render path never calls a model or a network — it satisfies the same
 * offline/airplane/no-telemetry red lines as PA.1.
 *
 * Coordinates live in an abstract layout space (not pixels); the renderer fits [bounds] to the
 * viewport. [clusters] + the [visibleAt] level-of-detail ladder (PA.5b) let the renderer collapse a
 * 300-node mesh to ≤12 super-nodes at overview zoom and expand on the way in — the answer to
 * "node soup at scale."
 */
@Serializable
data class GraphScene(
    val nodes: List<SceneNode>,
    /** Edges reference [nodes] by index (deduped by unordered endpoints + kind, so each pair draws once). */
    val edges: List<SceneEdge>,
    val bounds: SceneBounds,
    /** Communities (PA.5b); canonically ordered (largest first). Empty before clustering. */
    val clusters: List<SceneCluster> = emptyList(),
) {
    /**
     * The render set for a zoom [tier] — the heart of level-of-detail. Pure + deterministic, so the
     * "overview shows ≤[OVERVIEW_MAX] things" guarantee is golden-testable off-device.
     * - [LodTier.OVERVIEW]: one super-node per real cluster (size ≥ 2) + a single "Unconnected"
     *   holding node for the rest, with inter-group edges bundled into one weighted meta-edge per pair.
     * - [LodTier.MID]: every paper, but only the top-degree ones labelled; all edges.
     * - [LodTier.DETAIL]: every paper labelled; all edges.
     */
    fun visibleAt(tier: LodTier): SceneView =
        when (tier) {
            LodTier.OVERVIEW -> overviewView()
            LodTier.MID -> paperView(labelTopByDegree = true)
            LodTier.DETAIL -> paperView(labelTopByDegree = false)
        }

    /** The nearest visible render node to ([x], [y]) at [tier] within its hit radius, or null. */
    fun hitTest(
        x: Double,
        y: Double,
        tier: LodTier,
        slop: Double = 0.0,
    ): RenderNode? {
        var best: RenderNode? = null
        var bestDist = Double.POSITIVE_INFINITY
        for (rn in visibleAt(tier).nodes) {
            val dx = rn.x - x
            val dy = rn.y - y
            val d = StrictMath.sqrt(dx * dx + dy * dy)
            if (d <= rn.radius + slop && d < bestDist) {
                best = rn
                bestDist = d
            }
        }
        return best
    }

    private fun paperView(labelTopByDegree: Boolean): SceneView {
        val labelled: Set<Int> =
            if (!labelTopByDegree) {
                nodes.indices.toSet()
            } else {
                val count = maxOf(1, StrictMath.ceil(nodes.size * MID_LABEL_FRACTION).toInt())
                nodes.indices
                    .sortedWith(compareByDescending<Int> { nodes[it].degree }.thenBy { it })
                    .take(count)
                    .toSet()
            }
        val rNodes =
            nodes.mapIndexed { i, n ->
                RenderNode(
                    x = n.x,
                    y = n.y,
                    label = if (i in labelled) n.title else "",
                    radius = PAPER_RADIUS,
                    kind = RenderKind.PAPER,
                    refId = i,
                    count = 1,
                )
            }
        val rEdges =
            edges.map { e ->
                RenderEdge(nodes[e.from].x, nodes[e.from].y, nodes[e.to].x, nodes[e.to].y, e.kind, e.weight)
            }
        return SceneView(rNodes, rEdges)
    }

    private fun overviewView(): SceneView {
        // Real clusters (communities) get a super-node; everything else folds into one holding node.
        val real = clusters.filter { it.memberIndices.size >= 2 }
        val keptReal = real.take(OVERVIEW_MAX - 1) // leave a slot for the "Unconnected" node

        // group id per node index: a kept cluster's id, or UNCONNECTED_GROUP for the holding bucket.
        val groupOf = IntArray(nodes.size) { UNCONNECTED_GROUP }
        for (c in keptReal) for (m in c.memberIndices) groupOf[m] = c.id

        val rNodes = ArrayList<RenderNode>(keptReal.size + 1)
        for (c in keptReal) {
            rNodes.add(
                RenderNode(
                    x = c.x,
                    y = c.y,
                    label = c.label,
                    radius = clusterRadius(c.memberIndices.size),
                    kind = RenderKind.CLUSTER,
                    refId = c.id,
                    count = c.memberIndices.size,
                ),
            )
        }
        val loose = nodes.indices.filter { groupOf[it] == UNCONNECTED_GROUP }
        if (loose.isNotEmpty()) {
            rNodes.add(
                RenderNode(
                    x = loose.sumOf { nodes[it].x } / loose.size,
                    y = loose.sumOf { nodes[it].y } / loose.size,
                    label = "",
                    radius = clusterRadius(loose.size),
                    kind = RenderKind.UNCONNECTED,
                    refId = UNCONNECTED_GROUP,
                    count = loose.size,
                ),
            )
        }

        // Bundle every crossing edge into one weighted meta-edge per unordered group pair. Group ids
        // are offset by GROUP_KEY_OFFSET before packing so the -1 "unconnected" group stays
        // non-negative (clean integer div/mod) and keys are order-independent (lo ≤ hi).
        val crossings = LinkedHashMap<Long, Int>()
        for (e in edges) {
            val ga = groupOf[e.from]
            val gb = groupOf[e.to]
            if (ga == gb) continue
            val lo = minOf(ga, gb) + GROUP_KEY_OFFSET
            val hi = maxOf(ga, gb) + GROUP_KEY_OFFSET
            val key = lo.toLong() * GROUP_KEY_STRIDE + hi.toLong()
            crossings[key] = (crossings[key] ?: 0) + 1
        }
        val centroidOf = rNodes.associateBy { it.refId }
        val rEdges =
            crossings.entries.mapNotNull { (key, count) ->
                val lo = (key / GROUP_KEY_STRIDE).toInt() - GROUP_KEY_OFFSET
                val hi = (key % GROUP_KEY_STRIDE).toInt() - GROUP_KEY_OFFSET
                val a = centroidOf[lo] ?: return@mapNotNull null
                val b = centroidOf[hi] ?: return@mapNotNull null
                RenderEdge(a.x, a.y, b.x, b.y, RelationEdgeKind.CITES, count.toDouble())
            }
        return SceneView(rNodes, rEdges)
    }

    companion object {
        /** Max things drawn at overview zoom (mirrors the PA.1 node cap so the entry view stays legible). */
        const val OVERVIEW_MAX = 12
        private const val MID_LABEL_FRACTION = 0.34
        private const val PAPER_RADIUS = 6.0
        private const val CLUSTER_BASE_RADIUS = 10.0

        /** The synthetic group id for the "Unconnected (N)" holding node. */
        const val UNCONNECTED_GROUP = -1

        // Group-pair keys must encode the -1 group, so offset ids to be non-negative before packing.
        private const val GROUP_KEY_OFFSET = 2
        private const val GROUP_KEY_STRIDE = 1_000_003L

        private fun clusterRadius(count: Int): Double = CLUSTER_BASE_RADIUS * StrictMath.sqrt(count.toDouble())
    }
}

/** One positioned paper. [x]/[y] are in layout space; [degree] is its deduped undirected edge count. */
@Serializable
data class SceneNode(
    val id: String,
    val title: String,
    val x: Double,
    val y: Double,
    val degree: Int,
    val inLibrary: Boolean,
    val isCenter: Boolean,
    /** Community assignment (PA.5b); -1 until clustered. */
    val clusterId: Int = -1,
    /** arXiv primary category, for the sparse-graph clustering fallback. */
    val primaryCategory: String? = null,
)

/** A drawn relation between two [GraphScene.nodes] (by index). [weight] is the cosine for SIMILAR. */
@Serializable
data class SceneEdge(
    val from: Int,
    val to: Int,
    val kind: RelationEdgeKind,
    val weight: Double? = null,
)

/** The axis-aligned extent of all node positions — the renderer fits this to the viewport. */
@Serializable
data class SceneBounds(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
}

/** A detected community (PA.5b). [label] is the highest-degree member's title; [x]/[y] is the centroid. */
@Serializable
data class SceneCluster(
    val id: Int,
    val label: String,
    val memberIndices: List<Int>,
    val x: Double,
    val y: Double,
    val radius: Double,
)

/** Zoom level the renderer asks for; [GraphScene.visibleAt] maps it to a render set. */
@Serializable
enum class LodTier { OVERVIEW, MID, DETAIL }

/** What a single drawn token is at a given tier. */
@Serializable
enum class RenderKind { PAPER, CLUSTER, UNCONNECTED }

/** A tier-resolved render set — exactly what to draw, in layout space, nothing to recompute. */
@Serializable
data class SceneView(
    val nodes: List<RenderNode>,
    val edges: List<RenderEdge>,
)

/**
 * One drawn token: a [RenderKind.PAPER] (one paper) or a collapsed [RenderKind.CLUSTER] /
 * [RenderKind.UNCONNECTED] super-node. [refId] is the node index for a paper, the cluster id for a
 * cluster, or [GraphScene.UNCONNECTED_GROUP] for the holding node. [label] is "" when unlabelled at
 * this tier.
 */
@Serializable
data class RenderNode(
    val x: Double,
    val y: Double,
    val label: String,
    val radius: Double,
    val kind: RenderKind,
    val refId: Int,
    val count: Int,
)

/** A drawn edge in layout space. [weight] is a cosine (SIMILAR), a crossing count (overview meta-edge), or null. */
@Serializable
data class RenderEdge(
    val fromX: Double,
    val fromY: Double,
    val toX: Double,
    val toY: Double,
    val kind: RelationEdgeKind,
    val weight: Double? = null,
)
