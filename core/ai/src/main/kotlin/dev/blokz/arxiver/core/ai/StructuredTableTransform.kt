package dev.blokz.arxiver.core.ai

/** A parsed sentinel-intermediate comparison table (P-Atlas PA.4), pre-render. Pure data. */
data class ComparisonTable(
    val header: List<String>,
    /** Each row normalized to [header].size cells (short rows padded, overflow folded into the last cell). */
    val rows: List<List<String>>,
    /** 0.0..1.0; the gate renders a GFM table only at/above [StructuredTableTransform.CONFIDENCE_FLOOR]. */
    val parseConfidence: Double,
)

/**
 * "The app draws the structure; the AI narrates it" — applied to **tables** (P-Atlas PA.4). The
 * on-device STRUCTURED tier (Gemma E2B) emits valid GFM table *syntax* only ~40–85% of the time, so
 * instead we ask it for a **low-syntax intermediate** it nails 0-shot (arXiv:2506.19512): a block
 * opened by a line `TABLE::`, closed by a line `::TABLE`, one row per line, cells split by `~|~`
 * (a composite sentinel that never collides with math `|x|`, code `a||b`, or markdown), with the
 * model's `[n]` citations kept inline. This pure transform parses that block and emits a
 * **guaranteed-valid GFM table**, or — when the parse is low-confidence — a grounded **bulleted
 * list** (a *good* outcome for a tiny model: the ~1B "table reversal", arXiv:2412.17189).
 *
 * Text outside a block is untouched; a body with no `TABLE::` fence is a cheap no-op (so cloud FULL,
 * the PLAIN tier, and ordinary prose answers pass through byte-identically). The richness *gate*
 * (apply only on STRUCTURED turns) lives at the call sites, keeping this a pure `String → String`
 * with no model call — deterministic, offline, golden-testable. A reformat-only re-ask is a PA.5
 * lever, owned by `ChatRepository` (which holds the provider), never this pure function.
 */
object StructuredTableTransform {
    /** A clean grounded 3×3 table scores ~1.0; two ragged rows of three drop below this → list fallback. */
    const val CONFIDENCE_FLOOR = 0.70

    private const val SENTINEL = "~|~"
    private const val OPEN = "TABLE::"
    private const val CLOSE = "::TABLE"
    private val CITATION = Regex("""\[\d{1,3}]""")

    /**
     * Replace each `TABLE:: … ::TABLE` block in [body] with a guaranteed-valid GFM table (confident
     * parse) or a grounded bulleted list (low confidence); leave all other text verbatim. No sentinel
     * artifact → [body] returned unchanged. Idempotent: a rendered table/list line never *trims* to a
     * bare `TABLE::` (the fence is matched on a trimmed full-line basis), so a second pass is a no-op.
     *
     * **Hardened against fence confusion (device-found, K11):** a small model sometimes opens a block
     * with the *closing* fence `::TABLE` (or emits a stray block with no opener at all), which the
     * happy path missed — leaking raw `~|~`/`::TABLE` to the user. So the gate now triggers on *any*
     * sentinel token, a block opens on *either* fence line, and a final scrub guarantees no bare `~|~`
     * ever survives even if it appears outside any fence.
     */
    fun transform(body: String): String {
        if (!body.contains(OPEN) && !body.contains(CLOSE) && !body.contains(SENTINEL)) return body
        val lines = body.lines()
        val out = ArrayList<String>(lines.size)
        // A rendered block must be set off by a blank line on each side, or commonmark folds a GFM
        // table glued to adjacent prose into a paragraph (renders as literal `| a | b |` pipes).
        var separateNext = false
        var i = 0
        while (i < lines.size) {
            // Tolerant opener: a stray `::TABLE` (fence confusion) opens a block too. A properly-opened
            // `TABLE::` block's own `::TABLE` closer is consumed by the inner loop below (i jumps past
            // it), so it never reaches here as a spurious opener.
            if (lines[i].trim() == OPEN || lines[i].trim() == CLOSE) {
                val block = ArrayList<String>()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    if (lines[j].trim() == CLOSE) {
                        closed = true
                        break
                    }
                    block.add(lines[j])
                    j++
                }
                val table = parse(block.joinToString("\n"))
                val rendered =
                    if (table.header.size >= 2 && table.parseConfidence >= CONFIDENCE_FLOOR) {
                        renderGfm(table)
                    } else {
                        renderList(table)
                    }
                if (rendered.isNotEmpty()) {
                    if (out.isNotEmpty() && out.last().isNotBlank()) out.add("") // blank line before
                    out.addAll(rendered.lines())
                    separateNext = true // blank line after, inserted lazily before the next non-blank line
                }
                i = if (closed) j + 1 else j
            } else {
                if (separateNext) {
                    if (lines[i].isNotBlank()) out.add("")
                    separateNext = false
                }
                out.add(lines[i])
                i++
            }
        }
        // Belt-and-suspenders: the tolerant fences above salvage the common malformed-block case into a
        // real table, but a bare `~|~` row outside *any* fence would still slip through — collapse it to
        // a readable separator so the machine sentinel never reaches the user (device-found, K11).
        val joined = out.joinToString("\n")
        return if (joined.contains(SENTINEL)) {
            // Collapse the sentinel + its surrounding spaces (but not newlines) to a readable separator.
            joined.replace(Regex("""[ \t]*${Regex.escape(SENTINEL)}[ \t]*"""), " — ")
        } else {
            joined
        }
    }

    /** Parse the inner block (between the fences) into a normalized [ComparisonTable] + a confidence. */
    internal fun parse(innerBlock: String): ComparisonTable {
        val rows = innerBlock.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return ComparisonTable(emptyList(), emptyList(), 0.0)
        val header = rows[0].split(SENTINEL).map { cleanCell(it) }
        val n = header.size
        val rawData = rows.drop(1).map { line -> line.split(SENTINEL).map { cleanCell(it) } }

        // Shape gate (folds the <2-columns / no-rows rule into the confidence as a hard 0 multiplier).
        if (n < 2 || rawData.isEmpty()) {
            return ComparisonTable(header, rawData.map { normalizeRow(it, maxOf(n, 1)) }, 0.0)
        }

        val normRows = rawData.map { normalizeRow(it, n) }
        val rowShapeOk = rawData.count { it.size == n }.toDouble() / rawData.size
        val cells = normRows.size * n
        val filled = normRows.sumOf { row -> row.count { it.isNotBlank() } }
        val fillRate = if (cells > 0) filled.toDouble() / cells else 0.0
        val groundRate = rawData.count { row -> row.any { CITATION.containsMatchIn(it) } }.toDouble() / rawData.size
        val confidence = 0.55 * rowShapeOk + 0.30 * fillRate + 0.15 * groundRate
        return ComparisonTable(header, normRows, confidence)
    }

    /** Happy path: a guaranteed-valid GFM table (pipes escaped, even columns). */
    internal fun renderGfm(t: ComparisonTable): String {
        fun line(cells: List<String>) = cells.joinToString(" | ", prefix = "| ", postfix = " |") { escapeCell(it) }
        val sep = t.header.joinToString(" | ", prefix = "| ", postfix = " |") { "---" }
        return (listOf(line(t.header), sep) + t.rows.map { line(it) }).joinToString("\n")
    }

    /** Fallback: a grounded bulleted list — readable, exports clean, never an empty/broken table. */
    internal fun renderList(t: ComparisonTable): String {
        if (t.rows.isEmpty()) return "" // header-only → collapse to nothing (surrounding prose remains)
        val n = t.header.size
        return t.rows.joinToString("\n") { row ->
            if (n >= 2) {
                val label = row.getOrElse(0) { "" }
                val rest =
                    (1 until n)
                        .filter { it < row.size && row[it].isNotBlank() }
                        .joinToString("; ") { j -> "${t.header[j]}: ${row[j]}" }
                "- **$label**" + if (rest.isNotEmpty()) " — $rest" else ""
            } else {
                "- " + row.joinToString(" ").trim()
            }
        }
    }

    /**
     * Trim a cell, drop a leading bullet, and strip a *balanced* surrounding bold/italic wrapper —
     * never touch `[n]` citations, and never strip a lone trailing/leading `*` (e.g. a `0.94*`
     * significance marker), which `trim('*')` would wrongly eat.
     */
    private fun cleanCell(cell: String): String {
        var s = cell.trim().removePrefix("- ").removePrefix("* ").trim()
        if (s.length >= 4 && s.startsWith("**") && s.endsWith("**")) {
            s = s.substring(2, s.length - 2).trim()
        } else if (s.length >= 2 && s.startsWith("*") && s.endsWith("*")) {
            s = s.substring(1, s.length - 1).trim()
        }
        return s.trim()
    }

    /** Make a parsed cell safe inside GFM: escape literal pipes, flatten newlines. Citations untouched. */
    private fun escapeCell(cell: String): String = cell.replace("|", "\\|").replace("\n", " ").trim()

    /** Normalize a row to [n] cells: pad short rows with blanks; fold an overflow tail into the last cell. */
    private fun normalizeRow(
        cells: List<String>,
        n: Int,
    ): List<String> =
        when {
            cells.size == n -> cells
            cells.size < n -> cells + List(n - cells.size) { "" }
            else -> cells.take(n - 1) + cells.drop(n - 1).joinToString(" ").trim()
        }
}
