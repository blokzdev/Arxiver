package dev.blokz.arxiver.core.search

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RagRetrieverTest {
    private fun chunk(
        id: Long,
        paperId: String,
        text: String,
        vector: FloatArray,
    ) = ScopedChunk(id, paperId, text, vector)

    private class FakeVectors(private val data: List<ScopedChunk>) : ChunkVectorSource {
        var lastScope: RetrievalScope? = null

        override suspend fun chunks(
            scope: RetrievalScope,
            limit: Int,
            offset: Int,
        ): List<ScopedChunk> {
            lastScope = scope
            return data.drop(offset).take(limit)
        }
    }

    private class FakeKeyword(private val hits: List<Pair<Long, Double>>) : ChunkKeywordSource {
        override suspend fun match(
            query: String,
            scope: RetrievalScope,
            limit: Int,
        ): List<Pair<Long, Double>> = hits.take(limit)
    }

    @Test
    fun `semantic-only ranks by cosine and gate drops the weak tail`() =
        runTest {
            val vectors =
                FakeVectors(
                    listOf(
                        chunk(1, "p", "near", floatArrayOf(1f, 0f)),
                        chunk(2, "p", "far", floatArrayOf(0f, 1f)),
                    ),
                )
            val retriever = RagRetriever(vectors, FakeKeyword(emptyList()))

            val out = retriever.retrieve(floatArrayOf(1f, 0f), "anything", RetrievalScope.Paper("p"))

            assertEquals(1L, out.first().chunkId)
            assertEquals(Provenance.SEMANTIC, out.first().provenance)
            assertTrue(out.none { it.chunkId == 2L }, "far chunk dropped by quality gate")
            assertEquals(RetrievalScope.Paper("p"), vectors.lastScope)
        }

    @Test
    fun `a chunk in both legs gets BOTH provenance`() =
        runTest {
            val vectors =
                FakeVectors(
                    listOf(
                        chunk(1, "p", "graphs", floatArrayOf(0f, 1f)),
                        chunk(2, "p", "other", floatArrayOf(1f, 0f)),
                    ),
                )
            // query favors chunk 1 semantically; keyword also returns chunk 1.
            val retriever = RagRetriever(vectors, FakeKeyword(listOf(1L to 9.0)))

            val out = retriever.retrieve(floatArrayOf(0f, 1f), "graphs", RetrievalScope.Collection(7))

            assertEquals(Provenance.BOTH, out.first { it.chunkId == 1L }.provenance)
        }

    @Test
    fun `dimension mismatch skips the semantic leg and keyword still retrieves`() =
        runTest {
            val vectors = FakeVectors(listOf(chunk(1, "p", "kw only", floatArrayOf(1f, 0f))))
            val retriever = RagRetriever(vectors, FakeKeyword(listOf(1L to 3.0)))

            // 3-d query vs 2-d chunks: no semantic contribution.
            val out = retriever.retrieve(floatArrayOf(1f, 0f, 0f), "kw", RetrievalScope.Paper("p"))

            assertEquals(1, out.size)
            assertEquals(Provenance.KEYWORD, out.single().provenance)
        }

    @Test
    fun `empty scope yields no chunks`() =
        runTest {
            val retriever = RagRetriever(FakeVectors(emptyList()), FakeKeyword(listOf(1L to 1.0)))
            assertTrue(retriever.retrieve(floatArrayOf(1f, 0f), "q", RetrievalScope.Paper("p")).isEmpty())
        }
}
