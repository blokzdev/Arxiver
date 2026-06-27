package dev.blokz.arxiver.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Golden tests for the PA.4 "app-draws-the-structure" table transform. Pure JVM, mirroring
 * `RelationGraphBuilderTest`: the model's low-syntax `TABLE::` intermediate must become a
 * guaranteed-valid GFM table (confident) or a grounded bulleted list (low confidence), and a body
 * with no fence must pass through byte-identically (the FULL/PLAIN/no-table invariant).
 */
class StructuredTableTransformTest {
    private fun block(inner: String) = "TABLE::\n$inner\n::TABLE"

    // --- happy path: a guaranteed-valid GFM table ---

    @Test
    fun `a clean grounded comparison becomes an exact valid GFM table`() {
        val out =
            StructuredTableTransform.transform(
                block(
                    "Stage ~|~ Role ~|~ Data source\n" +
                        "Cold-start ~|~ bootstrap the policy [1] ~|~ curated SFT set [1]\n" +
                        "RL ~|~ refine via reward [2] ~|~ self-play rollouts [2]",
                ),
            )
        assertEquals(
            "| Stage | Role | Data source |\n" +
                "| --- | --- | --- |\n" +
                "| Cold-start | bootstrap the policy [1] | curated SFT set [1] |\n" +
                "| RL | refine via reward [2] | self-play rollouts [2] |",
            out,
        )
    }

    @Test
    fun `tolerant of ragged whitespace around the sentinel`() {
        val out =
            StructuredTableTransform.transform(
                block("Aspect~|~A ~|~  B\nSpeed ~|~ fast [1]~|~slower [2]"),
            )
        assertEquals(
            "| Aspect | A | B |\n| --- | --- | --- |\n| Speed | fast [1] | slower [2] |",
            out,
        )
    }

    @Test
    fun `a wide row folds the overflow into the last cell — table stays 3 columns`() {
        // Two clean grounded rows keep confidence above the floor; the one wide row's tail folds.
        val out =
            StructuredTableTransform.transform(
                block("A ~|~ B ~|~ C\n1 ~|~ 2 [1] ~|~ 3\nx ~|~ y ~|~ z ~|~ extra\n4 ~|~ 5 [2] ~|~ 6"),
            )
        assertTrue(out.contains("| --- | --- | --- |"), "renders as a 3-col table: $out")
        assertTrue(out.contains("| x | y | z extra |"), out)
    }

    @Test
    fun `a narrow row is padded with a blank cell`() {
        // Two clean grounded rows keep confidence above the floor; the one short row pads to 3 cells.
        val out =
            StructuredTableTransform.transform(
                block("A ~|~ B ~|~ C\n1 ~|~ 2 [1] ~|~ 3\nx ~|~ y\n4 ~|~ 5 [2] ~|~ 6"),
            )
        assertTrue(out.contains("| --- | --- | --- |"), out)
        assertTrue(out.lines().any { it.startsWith("| x | y |") }, "the short row pads to a blank 3rd cell: $out")
    }

    @Test
    fun `header stray markdown and bullets are stripped`() {
        val out = StructuredTableTransform.transform(block("**Aspect** ~|~ A ~|~ B\n- Speed ~|~ fast [1] ~|~ slow [2]"))
        assertTrue(out.startsWith("| Aspect | A | B |"))
        assertTrue(out.contains("| Speed | fast [1] | slow [2] |"))
    }

    @Test
    fun `a literal pipe inside a cell is escaped so the GFM never breaks`() {
        val out = StructuredTableTransform.transform(block("Expr ~|~ Meaning\nf(x) | g(x) ~|~ either [1]"))
        assertTrue(out.contains("| f(x) \\| g(x) | either [1] |"), out)
        // exactly one column separator's worth of unescaped pipes per cell boundary
        assertFalse(out.lines().last().count { it == '|' } > 4) // 3 borders, no stray
    }

    @Test
    fun `citations ride through verbatim`() {
        val out = StructuredTableTransform.transform(block("M ~|~ Score\nLoRA ~|~ 0.92 [2]\nFull ~|~ 0.94 [3]"))
        assertTrue(out.contains("0.92 [2]"))
        assertTrue(out.contains("0.94 [3]"))
    }

    // --- confidence gate → grounded list fallback ---

    @Test
    fun `a single-column block falls back to a grounded list, never a 1-col table`() {
        val out = StructuredTableTransform.transform(block("Findings\nThe method scales [1]\nIt is cheap [2]"))
        assertFalse(out.contains("| --- |"), "must not render a degenerate table")
        assertTrue(out.contains("- The method scales [1]"))
        assertTrue(out.contains("- It is cheap [2]"))
    }

    @Test
    fun `a low-confidence parse (all rows ragged) falls back to a grounded list`() {
        // Both data rows have 2 cells under a 3-col header → rowShapeOk=0 → confidence well below floor.
        val out = StructuredTableTransform.transform(block("A ~|~ B ~|~ C\nx ~|~ y\np ~|~ q"))
        assertFalse(out.contains("| --- |"), "ragged parse should not render a table")
        assertTrue(out.contains("- **x** — B: y"), out)
        assertTrue(out.contains("- **p** — B: q"), out)
    }

    @Test
    fun `a header with no data rows collapses to nothing (never an empty table)`() {
        val out = StructuredTableTransform.transform("Before.\n${block("A ~|~ B ~|~ C")}\nAfter.")
        assertFalse(out.contains("|"))
        assertEquals("Before.\nAfter.", out)
    }

    // --- splice / composition invariants ---

    @Test
    fun `a body with no fence is returned byte-identical (the FULL or PLAIN or prose invariant)`() {
        val body = "Here is a normal answer with a | pipe and ~5% and a [1] citation.\nSecond line."
        assertEquals(body, StructuredTableTransform.transform(body))
    }

    @Test
    fun `prose before and after the block is preserved verbatim`() {
        val out =
            StructuredTableTransform.transform(
                "Both stages serve roles.\n\n" +
                    block("S ~|~ R\nCold ~|~ boot [1]\nRL ~|~ refine [2]") +
                    "\n\nThe cold stage stabilizes RL [1].",
            )
        assertTrue(out.startsWith("Both stages serve roles.\n\n| S | R |"))
        assertTrue(out.endsWith("\n\nThe cold stage stabilizes RL [1]."))
    }

    @Test
    fun `two blocks are each transformed independently`() {
        val out =
            StructuredTableTransform.transform(
                "${block("A ~|~ B\n1 ~|~ 2 [1]")}\nmid\n${block("C ~|~ D\n3 ~|~ 4 [2]")}",
            )
        assertEquals(2, Regex("""\| --- \| --- \|""").findAll(out).count())
        assertTrue(out.contains("mid"))
    }

    @Test
    fun `transform is idempotent`() {
        val once = StructuredTableTransform.transform(block("A ~|~ B\nx ~|~ y [1]"))
        assertEquals(once, StructuredTableTransform.transform(once))
    }

    @Test
    fun `confidence crosses the floor as rows get cleaner`() {
        // 3 clean grounded rows → high confidence → table.
        val clean =
            StructuredTableTransform.transform(
                block("A ~|~ B ~|~ C\n1 ~|~ 2 [1] ~|~ 3\n4 ~|~ 5 [2] ~|~ 6\n7 ~|~ 8 [3] ~|~ 9"),
            )
        assertTrue(clean.contains("| --- | --- | --- |"))
    }

    // --- review-driven edges ---

    @Test
    fun `a table glued to surrounding prose is set off by blank lines so commonmark renders it`() {
        // commonmark folds a pipe-table glued to a paragraph into literal `| a | b |` text; the
        // transform must insert a blank line before AND after the rendered block.
        val out =
            StructuredTableTransform.transform("Here is the comparison.\n${block("A ~|~ B\nX ~|~ Y [1]")}\nDone.")
        assertTrue(out.contains("comparison.\n\n| A | B |"), "blank line before the table: $out")
        assertTrue(out.contains("| X | Y [1] |\n\nDone."), "blank line after the table: $out")
    }

    @Test
    fun `an unclosed TABLE fence (truncated output) still renders the rows it has`() {
        val out = StructuredTableTransform.transform("Intro.\nTABLE::\nA ~|~ B\nX ~|~ Y [1]")
        assertTrue(out.contains("| A | B |\n| --- | --- |\n| X | Y [1] |"), out)
        assertFalse(out.contains("TABLE::"))
    }

    @Test
    fun `the confidence gate is pinned at 0_70 — at the floor renders a table, below falls back to a list`() {
        // All rows shape-ok but half the cells blank: 0.55*1 + 0.30*0.5 + 0.15*0 = exactly 0.70 → table.
        val atFloor = StructuredTableTransform.transform(block("A ~|~ B\nX ~|~\n~|~ Y"))
        assertTrue(atFloor.contains("| --- | --- |"), "at the 0.70 floor → table: $atFloor")
        // Drop fill below half → confidence 0.625 < floor → list.
        val below = StructuredTableTransform.transform(block("A ~|~ B\nX ~|~\n~|~"))
        assertFalse(below.contains("| --- | --- |"), "below the floor → list: $below")
    }

    @Test
    fun `a TABLE mention inside a prose line is not treated as a fence`() {
        val body = "The format starts with TABLE:: on its own line, as shown."
        assertEquals(body, StructuredTableTransform.transform(body))
    }

    @Test
    fun `a trailing significance asterisk is preserved, not stripped as bold`() {
        val out = StructuredTableTransform.transform(block("Model ~|~ Score\nLoRA ~|~ 0.94*\nFull ~|~ 0.96"))
        assertTrue(out.contains("| LoRA | 0.94* |"), "the 0.94* marker survives cleanCell: $out")
    }

    // --- device-found fence confusion (K11): a small model opened a block with the *closing* fence ---

    @Test
    fun `a stray block opened by the closing fence is salvaged into a table, not leaked (device-found K11)`() {
        // Gemma sometimes opens a second block with `::TABLE` and the body has no `TABLE::` opener at all.
        val out =
            StructuredTableTransform.transform(
                "::TABLE\nSetting ~|~ Single ~|~ Multi\nEffect ~|~ improves [1] ~|~ diminishes [1]\n::TABLE",
            )
        assertTrue(out.contains("| Setting | Single | Multi |"), out)
        assertFalse(out.contains("~|~"), "no raw sentinel leaks: $out")
        assertFalse(out.contains("::TABLE"), "no orphan fence leaks: $out")
    }

    @Test
    fun `a direct GFM table followed by a stray sentinel block leaks neither (the exact K11 case)`() {
        // The model emitted a valid GFM table AND a stray `::TABLE` block (fence confusion).
        val out =
            StructuredTableTransform.transform(
                "| Setting | Observation |\n| :--- | :--- |\n| Single | improves [1] |\n\n" +
                    "::TABLE\nSetting ~|~ Single ~|~ Multi\nEffect ~|~ a [1] ~|~ b [1]\n::TABLE",
            )
        assertTrue(out.contains("| Setting | Observation |"), "the model's own GFM table is untouched: $out")
        assertFalse(out.contains("~|~"), "no raw sentinel leaks: $out")
        assertFalse(out.lines().any { it.trim() == "::TABLE" }, "no orphan fence line: $out")
    }

    @Test
    fun `a bare sentinel row with no fence at all is scrubbed, never shown raw`() {
        val out = StructuredTableTransform.transform("Quick note: A ~|~ B ~|~ C with no fence.")
        assertFalse(out.contains("~|~"), "the raw sentinel is collapsed: $out")
        assertTrue(out.contains("A — B — C"), out)
    }

    @Test
    fun `salvaging a stray fence block is idempotent`() {
        val once = StructuredTableTransform.transform("::TABLE\nA ~|~ B\nx ~|~ y [1]\n::TABLE")
        assertEquals(once, StructuredTableTransform.transform(once))
    }
}
