package dev.blokz.arxiver.core.search

/**
 * Computes a deterministic [GraphScene] from a [RelationGraph] (P-Atlas PA.5a) — the pure,
 * golden-testable core of the collection knowledge map. The renderer draws what this returns and
 * computes nothing, so the hard question ("is this node-soup at N=300?") is answerable from a JVM
 * snapshot, off-device.
 *
 * **Determinism is the load-bearing property.** The same [RelationGraph] in → byte-identical
 * coordinates out, on any JVM, *regardless of the input node/edge order*. Three things buy that:
 * 1. **Canonicalisation first** — nodes deduped + sorted by id; edges deduped by unordered
 *    endpoints+kind + sorted — so all summation happens in one fixed order (float `+` isn't
 *    associative, so order must be pinned).
 * 2. **No RNG / no wall-clock** — initial positions come from a Vogel (golden-angle) sunflower keyed
 *    on each node's *sorted rank*, not a seed or list index.
 * 3. **Only IEEE-deterministic math** — `+ - * /` and `StrictMath.{sin,cos,sqrt}` are
 *    correctly-rounded on every platform (plain `Math.sin/cos` is not), so Linux CI and a Windows
 *    dev box agree to the last bit; final coordinates are then rounded to [COORD_DECIMALS] dp.
 *
 * Disconnected components are laid out independently and **shelf-packed** with a gap, so two
 * unrelated clusters can never overlap (plain FR on a disconnected graph drifts unboundedly). At
 * collection scale the caller caps node count upstream (PA.5: top-K by centrality above ~400), so
 * the naïve O(n²) repulsion per component is bounded and there is no need for a Barnes–Hut tree here.
 */
object GraphLayoutEngine {
    /** Force-directed iterations per component — enough to untangle a few-hundred-node mesh. */
    const val ITERATIONS = 300

    /** Ideal edge length (= the FR constant k, since area is sized as n·SPACING²). */
    private const val SPACING = 50.0

    /** Gap between packed component bounding boxes — keeps disconnected pieces visibly apart. */
    private const val COMPONENT_GAP = 60.0

    /** Final coordinate precision: coarse enough to be golden-stable, fine enough to be faithful. */
    private const val COORD_DECIMALS = 2

    /** Vogel sunflower turn: π·(3−√5) ≈ 2.39996 rad — the most irrational angle, so points never line up. */
    private val GOLDEN_ANGLE = StrictMath.PI * (3.0 - StrictMath.sqrt(5.0))

    private val EMPTY_BOUNDS = SceneBounds(0.0, 0.0, 0.0, 0.0)

    /**
     * Lay [graph] out into a render-ready [GraphScene]. An empty graph yields an empty scene with
     * zero [SceneBounds]; a single node sits at the origin; otherwise each connected component is
     * force-directed and the components are shelf-packed.
     */
    fun layout(graph: RelationGraph): GraphScene {
        // 1. Canonicalise nodes: dedup by id, sort by id → the one true index space.
        val nodes = graph.nodes.distinctBy { it.id }.sortedBy { it.id }
        val n = nodes.size
        if (n == 0) return GraphScene(emptyList(), emptyList(), EMPTY_BOUNDS)
        val indexOf = HashMap<String, Int>(n * 2)
        nodes.forEachIndexed { i, node -> indexOf[node.id] = i }

        // 2. Canonical edges: among kept nodes, no self-loops, deduped by unordered endpoints+kind,
        //    sorted so the attraction pass (and the output) is order-independent.
        val edges = canonicalEdges(graph.edges, indexOf)

        // 3. Undirected edge-incidence degree per node.
        val degree = IntArray(n)
        for (e in edges) {
            degree[e.from]++
            degree[e.to]++
        }

        // 4. Connected components (union-find over the undirected, deduped edges).
        val components = components(n, edges)

        // 5. Lay out each component locally, then 6. shelf-pack into global space.
        val x = DoubleArray(n)
        val y = DoubleArray(n)
        val boxes = ArrayList<Box>(components.size)
        for (comp in components) {
            layoutComponent(comp, edges, x, y)
            boxes.add(boundingBox(comp, x, y))
        }
        packComponents(components, boxes, x, y)

        // 7. Round and assemble.
        val sceneNodes =
            nodes.mapIndexed { i, node ->
                SceneNode(
                    id = node.id,
                    title = node.title,
                    x = round(x[i]),
                    y = round(y[i]),
                    degree = degree[i],
                    inLibrary = node.inLibrary,
                    isCenter = node.isCenter,
                    primaryCategory = node.primaryCategory,
                )
            }
        val sceneEdges =
            edges.map { e ->
                SceneEdge(e.from, e.to, e.kind, e.weight?.let { round4(it) })
            }
        return GraphScene(sceneNodes, sceneEdges, boundsOf(sceneNodes))
    }

    // --- canonicalisation ---

    private data class IndexedEdge(val from: Int, val to: Int, val kind: RelationEdgeKind, val weight: Double?)

    private fun canonicalEdges(
        raw: List<RelationEdge>,
        indexOf: Map<String, Int>,
    ): List<IndexedEdge> {
        val seen = HashSet<Long>()
        val result = ArrayList<IndexedEdge>()
        // Sort by (from id, to id, kind) for a stable dedup-and-output order.
        val sorted =
            raw.sortedWith(
                compareBy({ it.from }, { it.to }, { it.kind.ordinal }),
            )
        for (e in sorted) {
            val a = indexOf[e.from] ?: continue
            val b = indexOf[e.to] ?: continue
            if (a == b) continue
            val lo = minOf(a, b)
            val hi = maxOf(a, b)
            // Pack (lo, hi, kind) into one key: unordered endpoints + kind dedup (mirrors PA.1).
            val key = (lo.toLong() * 1_000_003L + hi.toLong()) * 2L + e.kind.ordinal
            if (seen.add(key)) result.add(IndexedEdge(a, b, e.kind, e.weight))
        }
        return result
    }

    // --- connected components ---

    private fun components(
        n: Int,
        edges: List<IndexedEdge>,
    ): List<IntArray> {
        val parent = IntArray(n) { it }

        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var c = i
            while (parent[c] != c) {
                val next = parent[c]
                parent[c] = r
                c = next
            }
            return r
        }
        for (e in edges) {
            val ra = find(e.from)
            val rb = find(e.to)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }
        val byRoot = LinkedHashMap<Int, MutableList<Int>>()
        for (i in 0 until n) byRoot.getOrPut(find(i)) { ArrayList() }.add(i)
        // Order: larger components first, ties by smallest member index — fully deterministic.
        return byRoot.values
            .map { it.toIntArray() }
            .sortedWith(compareByDescending<IntArray> { it.size }.thenBy { it[0] })
    }

    // --- per-component force-directed layout ---

    private fun layoutComponent(
        comp: IntArray,
        allEdges: List<IndexedEdge>,
        x: DoubleArray,
        y: DoubleArray,
    ) {
        val m = comp.size
        if (m == 1) {
            x[comp[0]] = 0.0
            y[comp[0]] = 0.0
            return
        }
        // Local index for this component (rank within the component, which is sorted ascending).
        val localOf = HashMap<Int, Int>(m * 2)
        comp.forEachIndexed { local, global -> localOf[global] = local }

        // Vogel sunflower init keyed on local rank — deterministic, well-spread, symmetry-broken.
        val lx = DoubleArray(m)
        val ly = DoubleArray(m)
        for (r in 0 until m) {
            val radius = SPACING * StrictMath.sqrt(r + 0.5)
            val theta = r * GOLDEN_ANGLE
            lx[r] = radius * StrictMath.cos(theta)
            ly[r] = radius * StrictMath.sin(theta)
        }

        val localEdges =
            allEdges.filter { localOf.containsKey(it.from) && localOf.containsKey(it.to) }
                .map { it.from.let(localOf::getValue) to it.to.let(localOf::getValue) }

        val k = SPACING
        val k2 = k * k
        var temp = 5.0 * StrictMath.sqrt(m.toDouble()) // initial step cap; cools linearly to 0
        val cool = temp / (ITERATIONS + 1)
        val dx = DoubleArray(m)
        val dy = DoubleArray(m)

        repeat(ITERATIONS) {
            java.util.Arrays.fill(dx, 0.0)
            java.util.Arrays.fill(dy, 0.0)
            // Repulsion: every unordered pair once, applied symmetrically (Newton's third law).
            for (i in 0 until m) {
                for (j in i + 1 until m) {
                    var ddx = lx[i] - lx[j]
                    var ddy = ly[i] - ly[j]
                    var dist = StrictMath.sqrt(ddx * ddx + ddy * ddy)
                    if (dist < 1e-9) {
                        // Coincident: nudge deterministically along a stable axis so they part.
                        ddx = 1e-6
                        ddy = 0.0
                        dist = 1e-6
                    }
                    val force = k2 / dist
                    val ux = ddx / dist * force
                    val uy = ddy / dist * force
                    dx[i] += ux
                    dy[i] += uy
                    dx[j] -= ux
                    dy[j] -= uy
                }
            }
            // Attraction along edges.
            for ((a, b) in localEdges) {
                val ddx = lx[a] - lx[b]
                val ddy = ly[a] - ly[b]
                val dist = StrictMath.sqrt(ddx * ddx + ddy * ddy)
                if (dist < 1e-9) continue
                val force = dist * dist / k
                val ux = ddx / dist * force
                val uy = ddy / dist * force
                dx[a] -= ux
                dy[a] -= uy
                dx[b] += ux
                dy[b] += uy
            }
            // Move each node by its displacement, capped at the current temperature.
            for (i in 0 until m) {
                val d = StrictMath.sqrt(dx[i] * dx[i] + dy[i] * dy[i])
                if (d > 1e-9) {
                    val step = if (d < temp) d else temp
                    lx[i] += dx[i] / d * step
                    ly[i] += dy[i] / d * step
                }
            }
            temp -= cool
        }

        for (r in 0 until m) {
            x[comp[r]] = lx[r]
            y[comp[r]] = ly[r]
        }
    }

    // --- packing ---

    private data class Box(val minX: Double, val minY: Double, val width: Double, val height: Double)

    private fun boundingBox(
        comp: IntArray,
        x: DoubleArray,
        y: DoubleArray,
    ): Box {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (g in comp) {
            if (x[g] < minX) minX = x[g]
            if (y[g] < minY) minY = y[g]
            if (x[g] > maxX) maxX = x[g]
            if (y[g] > maxY) maxY = y[g]
        }
        return Box(minX, minY, maxX - minX, maxY - minY)
    }

    /**
     * Shelf-pack the component boxes left-to-right, wrapping to a new shelf past a target width
     * (≈ a square overall aspect). Translates each component's nodes so its box sits at the packed
     * slot. Shelf packing never overlaps boxes, which is what makes disconnected components separate.
     */
    private fun packComponents(
        components: List<IntArray>,
        boxes: List<Box>,
        x: DoubleArray,
        y: DoubleArray,
    ) {
        val totalArea = boxes.sumOf { (it.width + COMPONENT_GAP) * (it.height + COMPONENT_GAP) }
        val maxBoxWidth = boxes.maxOf { it.width }
        val targetWidth = maxOf(maxBoxWidth, StrictMath.sqrt(totalArea))

        var curX = 0.0
        var curY = 0.0
        var shelfHeight = 0.0
        for (idx in components.indices) {
            val comp = components[idx]
            val box = boxes[idx]
            if (curX > 0.0 && curX + box.width > targetWidth) {
                curX = 0.0
                curY += shelfHeight + COMPONENT_GAP
                shelfHeight = 0.0
            }
            // Offset that moves this box's (minX,minY) to (curX,curY).
            val offX = curX - box.minX
            val offY = curY - box.minY
            for (g in comp) {
                x[g] += offX
                y[g] += offY
            }
            curX += box.width + COMPONENT_GAP
            if (box.height > shelfHeight) shelfHeight = box.height
        }
    }

    // --- assembly helpers ---

    private fun boundsOf(nodes: List<SceneNode>): SceneBounds {
        if (nodes.isEmpty()) return EMPTY_BOUNDS
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (node in nodes) {
            if (node.x < minX) minX = node.x
            if (node.y < minY) minY = node.y
            if (node.x > maxX) maxX = node.x
            if (node.y > maxY) maxY = node.y
        }
        return SceneBounds(minX, minY, maxX, maxY)
    }

    private fun round(v: Double): Double {
        val factor = 100.0 // 10^COORD_DECIMALS
        return StrictMath.round(v * factor) / factor
    }

    private fun round4(v: Double): Double = StrictMath.round(v * 10_000.0) / 10_000.0
}
