package dev.blokz.arxiver.core.search

import org.junit.Test
import kotlin.test.assertEquals

class DotSimilarityTest {
    @Test
    fun `dot product is cosine for normalized vectors`() {
        val x = floatArrayOf(1f, 0f)
        val y = floatArrayOf(0f, 1f)
        val mixed = floatArrayOf(0.6f, 0.8f)

        assertEquals(1.0, dotSimilarity(x, x), 1e-9)
        assertEquals(0.0, dotSimilarity(x, y), 1e-9)
        assertEquals(0.6, dotSimilarity(x, mixed), 1e-6)
        assertEquals(0.8, dotSimilarity(y, mixed), 1e-6)
    }
}
