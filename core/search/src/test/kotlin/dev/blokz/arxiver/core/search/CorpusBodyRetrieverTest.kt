package dev.blokz.arxiver.core.search

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CorpusBodyRetrieverTest {
    private fun retriever(vararg matches: Pair<String, Double>) =
        CorpusBodyRetriever(
            source =
                object : CorpusBodyKeywordSource {
                    override suspend fun matchBody(
                        query: String,
                        limit: Int,
                    ): List<Pair<String, Double>> = matches.toList()
                },
        )

    @Test
    fun `rolls chunk scores up to paper by MAX, not SUM`() =
        runTest {
            // A: three weak chunks (max 0.3, sum 0.6). B: one strong chunk (0.5).
            val ranked = retriever("A" to 0.2, "A" to 0.3, "A" to 0.1, "B" to 0.5).retrieve("q")
            assertEquals(
                listOf("B", "A"),
                ranked,
                "MAX rollup: B's single strong hit outranks A — a SUM would wrongly flip it",
            )
        }

    @Test
    fun `ranks best-body-match-first and caps at k`() =
        runTest {
            val many = (1..30).map { "p$it" to it.toDouble() }.toTypedArray()
            val ranked = retriever(*many).retrieve("q", k = 5)
            assertEquals(listOf("p30", "p29", "p28", "p27", "p26"), ranked)
        }

    @Test
    fun `no matches yields empty`() =
        runTest {
            assertEquals(emptyList(), retriever().retrieve("q"))
        }
}
