package dev.blokz.arxiver.core.search

import kotlinx.serialization.Serializable

/**
 * A laid-out, render-ready relation graph (P-Atlas PA.5) — the "layout as data" artifact. Where
 * [RelationGraphBuilder] emits Mermaid text for the single-paper static star (PA.1), the
 * collection-scale knowledge map computes its *entire* geometry deterministically in this pure
 * `:core` module ([GraphLayoutEngine]) and hands the dumb on-screen renderer a [GraphScene] it only
 * has to blit + pan/zoom. Because the scene is pure data (and `@Serializable`), legibility is a JVM
 * golden you can eyeball before any device boots, and the render path never calls a model or a
 * network — it satisfies the same offline/airplane/no-telemetry red lines as PA.1.
 *
 * Coordinates live in an abstract layout space (not pixels); the renderer fits [bounds] to the
 * viewport. Clustering + level-of-detail tiers are layered on in PA.5b and ride as added fields.
 */
@Serializable
data class GraphScene(
    val nodes: List<SceneNode>,
    /** Edges reference [nodes] by index (deduped by unordered endpoints + kind, so each pair draws once). */
    val edges: List<SceneEdge>,
    val bounds: SceneBounds,
)

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
