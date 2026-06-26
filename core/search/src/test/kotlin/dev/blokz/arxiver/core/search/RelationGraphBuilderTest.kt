package dev.blokz.arxiver.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure golden-Mermaid tests for the app-drawn relation graph (P-Atlas PA.1). */
class RelationGraphBuilderTest {
    private fun center(id: String = "2403.00001") = RelationNode(id, "Center paper", isCenter = true)

    @Test
    fun `well-formed graph fences mermaid with nodes and edges`() {
        val graph =
            RelationGraph(
                nodes =
                    listOf(
                        center(),
                        RelationNode("2401.11111", "A library neighbor", inLibrary = true),
                        RelationNode("2402.22222", "An external neighbor", inLibrary = false),
                    ),
                edges =
                    listOf(
                        RelationEdge("2403.00001", "2401.11111", RelationEdgeKind.SIMILAR, 0.873),
                        RelationEdge("2403.00001", "2402.22222", RelationEdgeKind.CITES),
                    ),
            )
        val out = RelationGraphBuilder.toMermaid(graph)!!
        // Routes to the WebView path (RichContent.has matches a bare ```mermaid fence).
        assertTrue(out.startsWith("```mermaid\ngraph LR"), "fenced mermaid flowchart (LR = phone-friendly tall layout)")
        assertTrue(out.trimEnd().endsWith("```"), "closes the fence")
        assertTrue(out.contains("n0{{\"Center paper\"}}"), "center is a hexagon")
        assertTrue(out.contains("n1[\"A library neighbor\"]"), "library node is a rectangle")
        assertTrue(out.contains("n2(\"An external neighbor\")"), "external node is rounded")
        assertTrue(out.contains("n0 -.->|0.87| n1"), "similarity edge carries a 2dp cosine")
        assertTrue(out.contains("n0 -->|cites| n2"), "citation edge is solid + labelled")
    }

    @Test
    fun `node cap is enforced`() {
        val nodes = listOf(center()) + (1..20).map { RelationNode("id$it", "N$it") }
        val edges = (1..20).map { RelationEdge("2403.00001", "id$it", RelationEdgeKind.SIMILAR, 0.5) }
        val out = RelationGraphBuilder.toMermaid(RelationGraph(nodes, edges))!!
        val nodeLines = out.lines().count { it.trim().matches(Regex("n\\d+[\\[({].*")) }
        assertEquals(RelationGraphBuilder.MAX_NODES, nodeLines, "no more than MAX_NODES nodes drawn")
        assertFalse(out.contains("\"N15\""), "nodes beyond the cap are dropped")
    }

    @Test
    fun `adversarial title chars are escaped`() {
        // Short enough that every special char survives the length cap. The definite Mermaid
        // breakers — " (closes the quote), # (entity prefix), $ (KaTeX) — must be neutralised.
        val nasty = "\"q\" \$x\$ #h | (p)"
        val graph =
            RelationGraph(
                nodes = listOf(center(), RelationNode("2401.11111", nasty, inLibrary = true)),
                edges = listOf(RelationEdge("2403.00001", "2401.11111", RelationEdgeKind.SIMILAR, 0.9)),
            )
        val out = RelationGraphBuilder.toMermaid(graph)!!
        assertFalse(out.contains("\$"), "dollar signs stripped (no KaTeX trip inside a label)")
        assertFalse(out.contains("\"q\""), "raw quotes escaped to the Mermaid entity")
        assertTrue(out.contains("#quot;q#quot;"), "quotes become #quot;")
        assertTrue(out.contains("#35;h"), "a literal # becomes its entity")
    }

    @Test
    fun `whitespace is collapsed and long titles are capped with an ellipsis`() {
        val long = "word\n\t ".repeat(30).trim()
        val graph =
            RelationGraph(
                nodes = listOf(center(), RelationNode("2401.11111", long, inLibrary = true)),
                edges = listOf(RelationEdge("2403.00001", "2401.11111", RelationEdgeKind.SIMILAR, 0.5)),
            )
        val out = RelationGraphBuilder.toMermaid(graph)!!
        val labelLine = out.lines().first { it.contains("n1[") }
        assertFalse(labelLine.contains("\n") || labelLine.contains("\t"), "no raw whitespace in a label")
        assertTrue(labelLine.contains("…"), "long label is capped with an ellipsis")
        assertTrue(labelLine.length < 60, "label is capped near MAX_LABEL")
    }

    @Test
    fun `no nodes yields null`() {
        assertNull(RelationGraphBuilder.toMermaid(RelationGraph(emptyList(), emptyList())))
    }

    @Test
    fun `nodes with no edges among them yields null (caller shows empty state)`() {
        val graph =
            RelationGraph(
                nodes = listOf(center(), RelationNode("2401.11111", "Orphan")),
                edges = emptyList(),
            )
        assertNull(RelationGraphBuilder.toMermaid(graph), "a graph with no edges isn't a map")
    }

    @Test
    fun `edges to dropped or missing nodes are filtered, self-loops removed`() {
        val graph =
            RelationGraph(
                nodes = listOf(center(), RelationNode("2401.11111", "Real")),
                edges =
                    listOf(
                        RelationEdge("2403.00001", "2401.11111", RelationEdgeKind.CITES),
                        // endpoint not a kept node
                        RelationEdge("2403.00001", "missing", RelationEdgeKind.CITES),
                        // self-loop
                        RelationEdge("2403.00001", "2403.00001", RelationEdgeKind.SIMILAR, 1.0),
                    ),
            )
        val out = RelationGraphBuilder.toMermaid(graph)!!
        assertEquals(1, out.lines().count { it.contains("-->") || it.contains("-.->") }, "only the valid edge")
    }
}
