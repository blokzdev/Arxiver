package dev.blokz.arxiver.core.search

import kotlinx.serialization.Serializable

/**
 * A node in an app-drawn relation graph (P-Atlas PA.1): a paper, identified by its arXiv id.
 * [isCenter] marks the paper the graph radiates from (drawn distinctly); [inLibrary] drives the
 * node shape so a reader can tell library papers from external ones at a glance.
 */
data class RelationNode(
    val id: String,
    val title: String,
    val inLibrary: Boolean = false,
    val isCenter: Boolean = false,
)

/** How two papers relate. [CITES] = a citation edge; [SIMILAR] = a high embedding cosine. */
@Serializable
enum class RelationEdgeKind { CITES, SIMILAR }

/** A directed edge [from] → [to]; [weight] is the cosine for [SIMILAR], null for [CITES]. */
data class RelationEdge(
    val from: String,
    val to: String,
    val kind: RelationEdgeKind,
    val weight: Double? = null,
)

/** The raw relationship structure a feeder produces and [RelationGraphBuilder] renders to Mermaid. */
data class RelationGraph(
    val nodes: List<RelationNode>,
    val edges: List<RelationEdge>,
)
