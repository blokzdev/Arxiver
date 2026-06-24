package dev.blokz.arxiver.feature.paper.ask

import dev.blokz.arxiver.data.Citation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure tests for the R3b.2 local follow-up generator — selection, dedup, floor, language guard. */
class FollowUpHeuristicsTest {
    private fun cite(
        index: Int,
        paperId: String,
    ) = Citation(index, paperId, "excerpt")

    private fun gen(
        question: String,
        answer: String = "A short grounded answer.",
        citations: List<Citation> = listOf(cite(1, "2401.00001")),
        prior: List<String> = emptyList(),
    ) = FollowUpHeuristics.followUps(question, answer, citations, prior)

    @Test
    fun `returns two to three for a generic question`() {
        val out = gen("What is this paper about?")
        assertTrue(out.size in 2..3, "got ${out.size}: $out")
    }

    @Test
    fun `cross-paper leads only with two or more distinct papers`() {
        val multi = gen("What is this about?", citations = listOf(cite(1, "2401.00001"), cite(2, "2402.00002")))
        assertEquals(FollowUpHeuristics.CROSS_PAPER, multi.first())

        val single = gen("What is this about?", citations = listOf(cite(1, "2401.00001"), cite(2, "2401.00001")))
        assertFalse(single.contains(FollowUpHeuristics.CROSS_PAPER), "one distinct paper → no cross-paper")
    }

    @Test
    fun `drops a template whose intent was already asked`() {
        assertFalse(gen("What are the limitations?").contains(FollowUpHeuristics.LIMITATIONS))
        assertFalse(gen("How does the method work?").contains(FollowUpHeuristics.METHOD))
        assertFalse(gen("How does this compare to prior work?").contains(FollowUpHeuristics.COMPARE))
    }

    @Test
    fun `summarize prompt still offers method and compare`() {
        val out = gen(AskViewModel.SUMMARIZE_PROMPT)
        assertTrue(out.contains(FollowUpHeuristics.METHOD), "got $out")
        assertTrue(out.contains(FollowUpHeuristics.COMPARE), "got $out")
    }

    @Test
    fun `a long method-heavy answer suppresses the explain-method chip`() {
        val longMethod = "This paper's method works by ".padEnd(700, 'x')
        assertFalse(gen("What is this about?", answer = longMethod).contains(FollowUpHeuristics.METHOD))
        // A short answer keeps it.
        assertTrue(gen("What is this about?", answer = "Short.").contains(FollowUpHeuristics.METHOD))
    }

    @Test
    fun `prior questions are excluded across the session`() {
        val out = gen("What is this about?", prior = listOf("What are the limitations?"))
        assertFalse(out.contains(FollowUpHeuristics.LIMITATIONS), "limitations already asked earlier")
    }

    @Test
    fun `the floor guarantees at least two even when every intent was asked`() {
        val out =
            gen(
                "limitations, how does the method work, compare to prior work, key results, assumptions, " +
                    "future work, in simple terms, how do these relate",
            )
        assertTrue(out.size >= 2, "floor backstop must keep ≥2; got $out")
    }

    @Test
    fun `self-equality drops a template passed verbatim as the question`() {
        assertFalse(gen(FollowUpHeuristics.LIMITATIONS).contains(FollowUpHeuristics.LIMITATIONS))
    }

    @Test
    fun `deterministic for identical inputs`() {
        assertEquals(gen("What is this about?"), gen("What is this about?"))
    }

    @Test
    fun `a largely non-English answer yields no chips`() {
        val chinese = "这是一篇关于方法与结果的中文论文摘要，讨论了局限性与未来工作。".repeat(4)
        assertTrue(gen("这是关于什么的？", answer = chinese).isEmpty())
    }
}
