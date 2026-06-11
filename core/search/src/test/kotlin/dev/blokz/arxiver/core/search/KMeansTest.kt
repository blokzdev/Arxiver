package dev.blokz.arxiver.core.search

import org.junit.Test
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KMeansTest {
    private fun unit(vararg values: Float): FloatArray {
        val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(values.size) { values[it] / norm }
    }

    @Test
    fun `separates two obvious clusters`() {
        val clusterA = List(10) { unit(1f, 0.01f * it, 0f) }
        val clusterB = List(10) { unit(0f, 0.01f * it, 1f) }
        val centroids = KMeans.centroids(clusterA + clusterB, k = 2)

        assertEquals(2, centroids.size)
        val probeA = unit(1f, 0f, 0f)
        val probeB = unit(0f, 0f, 1f)
        assertTrue(KMeans.similarityToNearest(probeA, centroids) > 0.95)
        assertTrue(KMeans.similarityToNearest(probeB, centroids) > 0.95)
        // A probe orthogonal-ish to both clusters scores low.
        assertTrue(KMeans.similarityToNearest(unit(0f, 1f, 0f), centroids) < 0.5)
    }

    @Test
    fun `k larger than corpus collapses to corpus size`() {
        val vectors = List(3) { unit(1f, it.toFloat(), 0f) }
        assertEquals(3, KMeans.centroids(vectors, k = 5).size)
    }

    @Test
    fun `empty corpus yields no centroids and zero similarity`() {
        assertTrue(KMeans.centroids(emptyList()).isEmpty())
        assertEquals(0.0, KMeans.similarityToNearest(unit(1f, 0f, 0f), emptyList()))
    }

    @Test
    fun `deterministic across runs`() {
        val vectors = List(20) { unit(it.toFloat() + 1, (it % 3).toFloat(), 1f) }
        val first = KMeans.centroids(vectors, k = 3).map { it.toList() }
        val second = KMeans.centroids(vectors, k = 3).map { it.toList() }
        assertEquals(first, second)
    }
}
