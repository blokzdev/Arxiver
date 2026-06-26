package dev.blokz.arxiver.core.search

import java.util.Locale

/**
 * Renders a [RelationGraph] into a **valid-by-construction** Mermaid flowchart fenced block
 * (P-Atlas PA.1) — the app draws the structure deterministically instead of asking an LLM to emit
 * Mermaid (which fails ~15–20% of syntax checks per MermaidSeqBench/StructEval). The output renders
 * unchanged through the existing offline WebView path: a ` ```mermaid ` fence is detected by
 * `RichContent.has`, and the block's text is HTML-escaped by commonmark then read back via Mermaid's
 * `textContent` inside a `<pre>` (which KaTeX's auto-render ignores, so `$` never trips it).
 *
 * Pure (no Android / no DB) → fully unit-testable. Applies the mobile graph-viz guidance: a hard
 * node cap for small screens, center / in-library / external node **shapes** (theme-safe — no
 * hardcoded colors, so Mermaid's light/dark `themeVariables` drive the palette), deduped edges, and
 * labels escaped for `securityLevel:'strict'`.
 */
object RelationGraphBuilder {
    /** Hard cap on rendered nodes — keeps a phone-screen graph legible (avoids node-soup). */
    const val MAX_NODES = 12
    private const val MAX_LABEL = 40

    /**
     * A Mermaid fenced block for [graph], or null when there is nothing meaningful to draw (no
     * nodes, or no edges among the kept nodes) — the caller shows empty-state copy instead.
     */
    fun toMermaid(graph: RelationGraph): String? {
        if (graph.nodes.isEmpty()) return null
        // Keep the center first, then others in feeder order, deduped by id, capped for the screen.
        val center = graph.nodes.firstOrNull { it.isCenter }
        val ordered = (listOfNotNull(center) + graph.nodes.filterNot { it.isCenter }).distinctBy { it.id }
        val kept = ordered.take(MAX_NODES)
        val keptIds = kept.map { it.id }.toSet()
        // Index-based ids (n0, n1, …) are unconditionally Mermaid-safe — arXiv ids carry `.`/`/`.
        val mid = kept.mapIndexed { i, n -> n.id to "n$i" }.toMap()

        // Edges only among kept nodes, no self-loops, deduped by unordered endpoints + kind.
        val edges =
            graph.edges
                .filter { it.from != it.to && it.from in keptIds && it.to in keptIds }
                .distinctBy { Triple(minOf(it.from, it.to), maxOf(it.from, it.to), it.kind) }
        if (edges.isEmpty()) return null

        val sb = StringBuilder()
        // LR (left→right), not TD: a star graph (center + N neighbours) fans out *horizontally* in
        // TD and gets squished to phone width; LR makes it tall + narrow, so it fits the chat
        // bubble width at a readable size while the WebView self-sizes its (scrollable) height.
        sb.append("```mermaid\n").append("graph LR\n")
        for (n in kept) {
            val id = mid.getValue(n.id)
            val label = escapeLabel(n.title)
            val node =
                when {
                    n.isCenter -> "$id{{\"$label\"}}" // hexagon — the paper in focus
                    n.inLibrary -> "$id[\"$label\"]" // rectangle — already in your library
                    else -> "$id(\"$label\")" // rounded — external / related
                }
            sb.append("  ").append(node).append('\n')
        }
        for (e in edges) {
            val a = mid.getValue(e.from)
            val b = mid.getValue(e.to)
            when (e.kind) {
                RelationEdgeKind.CITES -> sb.append("  $a -->|cites| $b\n")
                RelationEdgeKind.SIMILAR ->
                    if (e.weight != null) {
                        sb.append("  $a -.->|${formatCosine(e.weight)}| $b\n")
                    } else {
                        sb.append("  $a -.-> $b\n")
                    }
            }
        }
        sb.append("```")
        return sb.toString()
    }

    private fun formatCosine(v: Double): String = String.format(Locale.ROOT, "%.2f", v)

    /**
     * Make [raw] safe inside a Mermaid quoted label under `securityLevel:'strict'`: collapse
     * whitespace, cap length, escape `#` (the Mermaid entity prefix) then `"` (→ `#quot;`), and drop
     * `$` (belt-and-suspenders against KaTeX, even though the mermaid `<pre>` is in KaTeX's ignored
     * tags). Other punctuation is safe inside a quoted label.
     */
    private fun escapeLabel(raw: String): String {
        val oneLine = raw.replace(WHITESPACE, " ").trim()
        val capped = if (oneLine.length > MAX_LABEL) oneLine.take(MAX_LABEL).trimEnd() + "…" else oneLine
        return capped
            .replace("#", "#35;")
            .replace("\"", "#quot;")
            .replace("$", "")
    }

    private val WHITESPACE = Regex("\\s+")
}
