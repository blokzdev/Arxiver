package dev.blokz.arxiver.core.search

/**
 * Assigns the positioned [GraphScene] into communities (P-Atlas PA.5b) so the level-of-detail ladder
 * can collapse a hairball to a handful of super-nodes. Pure + deterministic, like the rest of the
 * layout core.
 *
 * **Primary method — Louvain modularity local-moving.** Each node starts in its own community and
 * repeatedly moves to the neighbouring community that most increases modularity; passes repeat until
 * no move helps. Unlike deterministic label propagation (whose min-label tie-break floods across any
 * bridge and collapses a connected graph into one blob), modularity correctly *subdivides* a
 * connected collection — a single citation bridge between two dense topics can't merge them, which is
 * exactly what makes the inter-cluster meta-edges at overview meaningful. Determinism comes from a
 * fixed node order, preferring to stay put on a tie, and breaking remaining ties by smallest
 * community id (independent of hash-map iteration order).
 *
 * **Fallback — by arXiv category:** a realistic collection graph is often *too sparse* to form
 * communities (after in-collection filtering, similarity neighbours frequently fall outside the set,
 * and citations need a synced key). When there are no edges at all (every node its own community),
 * the map groups by [SceneNode.primaryCategory] instead — so "all my cs.LG papers" still reads as a
 * cluster rather than 80 lonely dots.
 *
 * Only clusters of size ≥ 2 are emitted as [SceneCluster]s; singletons get [GraphScene.UNCONNECTED_GROUP]
 * as their [SceneNode.clusterId] and are folded into the "Unconnected (N)" holding node at overview zoom.
 */
object GraphClusterer {
    private const val MAX_PASSES = 50
    private const val EPS = 1e-12

    /** Return [scene] with [SceneNode.clusterId] assigned and [GraphScene.clusters] populated. */
    fun cluster(scene: GraphScene): GraphScene {
        val n = scene.nodes.size
        if (n == 0) return scene

        val adjacency = Array(n) { ArrayList<Int>() }
        for (e in scene.edges) {
            adjacency[e.from].add(e.to)
            adjacency[e.to].add(e.from)
        }

        var labels = louvain(n, adjacency)
        // No community formed (no edges) → fall back to arXiv-category grouping if we can.
        if (largestGroupSize(labels) == 1) {
            categoryLabels(scene)?.let { labels = it }
        }
        return assemble(scene, labels)
    }

    /**
     * One level of Louvain local-moving: greedily move each node to the neighbouring community giving
     * the largest modularity gain (`k_i,in − Σtot·k_i / 2m`), preferring to stay on a tie. Modularity
     * only rises and is bounded, so this converges; [MAX_PASSES] is a safety cap.
     */
    private fun louvain(
        n: Int,
        adjacency: Array<ArrayList<Int>>,
    ): IntArray {
        val degree = IntArray(n) { adjacency[it].size }
        val m2 = degree.sumOf { it.toLong() }.toDouble() // = 2·(edge count)
        val community = IntArray(n) { it }
        if (m2 == 0.0) return community // no edges → all singletons

        val sigmaTot = DoubleArray(n) { degree[it].toDouble() } // total degree per community id

        repeat(MAX_PASSES) {
            var moved = false
            for (i in 0 until n) {
                if (adjacency[i].isEmpty()) continue
                val ci = community[i]
                val ki = degree[i].toDouble()

                // Edges from i into each neighbouring community.
                val kiIn = HashMap<Int, Int>()
                for (j in adjacency[i]) {
                    if (j == i) continue
                    val cj = community[j]
                    kiIn[cj] = (kiIn[cj] ?: 0) + 1
                }

                // Detach i from its current community before scoring candidates.
                sigmaTot[ci] -= ki
                val stayGain = (kiIn[ci] ?: 0) - sigmaTot[ci] * ki / m2
                var bestGain = stayGain
                var bestCommunity = ci
                for ((c, kin) in kiIn) {
                    if (c == ci) continue
                    val gain = kin - sigmaTot[c] * ki / m2
                    // Strictly better wins; on a tie keep the smallest community id (but never beat
                    // staying, which already holds bestCommunity == ci at the incoming bestGain).
                    if (gain > bestGain + EPS || (gain > bestGain - EPS && bestCommunity != ci && c < bestCommunity)) {
                        bestGain = gain
                        bestCommunity = c
                    }
                }
                sigmaTot[bestCommunity] += ki
                community[i] = bestCommunity
                if (bestCommunity != ci) moved = true
            }
            if (!moved) return community
        }
        return community
    }

    /** Grouping labels by [SceneNode.primaryCategory]; null when no node has a category (fallback is moot). */
    private fun categoryLabels(scene: GraphScene): IntArray? {
        if (scene.nodes.none { it.primaryCategory != null }) return null
        val firstIndexOfCategory = HashMap<String, Int>()
        return IntArray(scene.nodes.size) { i ->
            val cat = scene.nodes[i].primaryCategory
            // Null-category nodes stay singletons (own index); same category → same first-seen index.
            if (cat == null) i else firstIndexOfCategory.getOrPut(cat) { i }
        }
    }

    private fun largestGroupSize(labels: IntArray): Int {
        if (labels.isEmpty()) return 0
        val counts = HashMap<Int, Int>()
        var max = 0
        for (l in labels) {
            val c = (counts[l] ?: 0) + 1
            counts[l] = c
            if (c > max) max = c
        }
        return max
    }

    /** Canonicalise labels → cluster ids (real = size ≥ 2, ordered largest-first), build [SceneCluster]s. */
    private fun assemble(
        scene: GraphScene,
        labels: IntArray,
    ): GraphScene {
        val n = scene.nodes.size
        val groups = LinkedHashMap<Int, MutableList<Int>>()
        for (i in 0 until n) groups.getOrPut(labels[i]) { ArrayList() }.add(i)

        val realGroups =
            groups.values
                .filter { it.size >= 2 }
                .sortedWith(compareByDescending<List<Int>> { it.size }.thenBy { it[0] })

        val clusterIdOf = IntArray(n) { GraphScene.UNCONNECTED_GROUP }
        val clusters = ArrayList<SceneCluster>(realGroups.size)
        realGroups.forEachIndexed { cid, members ->
            for (m in members) clusterIdOf[m] = cid
            // Label = the highest-degree member's title (ties → smallest index) — a recognisable anchor.
            val rep = members.minWith(compareByDescending<Int> { scene.nodes[it].degree }.thenBy { it })
            val cx = members.sumOf { scene.nodes[it].x } / members.size
            val cy = members.sumOf { scene.nodes[it].y } / members.size
            val radius =
                members.maxOf { m ->
                    val dx = scene.nodes[m].x - cx
                    val dy = scene.nodes[m].y - cy
                    StrictMath.sqrt(dx * dx + dy * dy)
                }
            clusters.add(
                SceneCluster(
                    id = cid,
                    label = scene.nodes[rep].title,
                    memberIndices = members.sorted(),
                    x = round(cx),
                    y = round(cy),
                    radius = round(radius),
                ),
            )
        }

        val newNodes = scene.nodes.mapIndexed { i, node -> node.copy(clusterId = clusterIdOf[i]) }
        return scene.copy(nodes = newNodes, clusters = clusters)
    }

    private fun round(v: Double): Double = StrictMath.round(v * 100.0) / 100.0
}
