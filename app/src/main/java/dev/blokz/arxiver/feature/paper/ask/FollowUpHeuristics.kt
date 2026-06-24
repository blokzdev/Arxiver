package dev.blokz.arxiver.feature.paper.ask

import dev.blokz.arxiver.data.Citation

/**
 * Local, deterministic follow-up question generator (P-Rich R3b.2) for Quick/Standard answers and
 * any on-device answer — no LLM call, no prompt change, offline. Picks 2–3 useful next questions
 * from a curated template set, dropping any whose intent the user already asked (this turn or
 * earlier in the session) and any that self-equals an asked question. A cross-paper prompt leads
 * when the answer cites ≥2 distinct papers. Pure → unit-testable (mirrors [AskPresets]).
 *
 * Templates are English; on a largely non-English answer it returns empty (no mismatched chips)
 * rather than show English suggestions — Max's model-generated follow-ups match the answer language.
 */
object FollowUpHeuristics {
    const val LIMITATIONS = "What are the limitations or weaknesses?"
    const val METHOD = "Explain the method in more detail."
    const val COMPARE = "How does this compare to related work?"
    const val RESULTS = "What are the key results?"
    const val ASSUMPTIONS = "What assumptions does it rely on?"
    const val FUTURE = "What future work does it suggest?"
    const val ELI5 = "Explain this in simple terms."
    const val CROSS_PAPER = "How do these papers relate to each other?"

    /** Question-intent phrases per template; matched against the asked questions to avoid repeats. */
    private val INTENT: Map<String, List<String>> =
        mapOf(
            LIMITATIONS to listOf("limitation", "weakness", "drawback", "shortcoming"),
            METHOD to
                listOf("how does", "how do ", "explain the method", "methodology", "how it works", "the method work"),
            COMPARE to listOf("compare", "comparison", "related work", "prior work", " versus ", " vs "),
            RESULTS to
                listOf(
                    "what are the result", "key result", "main result", "the results",
                    "performance", "accuracy", "benchmark",
                ),
            ASSUMPTIONS to listOf("assumption", "assume"),
            FUTURE to listOf("future work", "next step", "extension"),
            ELI5 to listOf("simple terms", "eli5", "non-specialist", "layman", "plain language", "explain simply"),
            CROSS_PAPER to listOf("how do these", "relate to each other", "across these", "between these"),
        )

    /** Backstop pool to guarantee ≥2 chips even when dedup empties the primary selection. */
    private val FALLBACK_POOL = listOf(RESULTS, FUTURE, ASSUMPTIONS, ELI5)

    fun followUps(
        question: String,
        answer: String,
        citedChunks: List<Citation>,
        priorQuestions: List<String> = emptyList(),
    ): List<String> {
        // Mismatched-language guard: templates are English; skip on a largely non-English answer.
        if (answer.isNotBlank() && nonAsciiLetterRatio(answer) > 0.30) return emptyList()

        val asked = (priorQuestions + question).map(::normalize)
        val multiPaper = citedChunks.map { it.paperId }.distinct().size >= 2
        val answerLong = answer.length > 600
        val answerLower = answer.lowercase()

        val ordered =
            buildList {
                if (multiPaper) add(CROSS_PAPER)
                add(LIMITATIONS)
                add(METHOD)
                add(COMPARE)
                add(RESULTS)
                add(FUTURE)
                add(ASSUMPTIONS)
                add(ELI5)
            }

        val picked =
            ordered.filterNot { template ->
                val kws = INTENT[template].orEmpty()
                asked.any { q -> q == normalize(template) || kws.any { q.contains(it) } } ||
                    // If the answer already walks through the method at length, don't ask to explain it.
                    (
                        template == METHOD && answerLong &&
                            listOf("method", "approach", "algorithm").any { answerLower.contains(it) }
                    )
            }.toMutableList()

        // True floor: top up from the pool (ignoring dedup) so the row is never a lonely single chip.
        if (picked.size < 2) {
            for (f in FALLBACK_POOL) {
                if (f !in picked) picked.add(f)
                if (picked.size >= 2) break
            }
        }
        return picked.distinct().take(3)
    }

    private fun normalize(s: String): String = s.lowercase().trim().trimEnd('?', '.', '!', ' ')

    private fun nonAsciiLetterRatio(s: String): Double {
        val letters = s.filter { it.isLetter() }
        if (letters.isEmpty()) return 0.0
        return letters.count { it.code > 127 }.toDouble() / letters.length
    }
}
