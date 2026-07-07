package dev.blokz.arxiver.core.search

import org.junit.Test
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RocchioRankerTest {
    private fun unit(vararg values: Float): FloatArray {
        val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(values.size) { values[it] / norm }
    }

    // Positive interest sits around the +x axis; dislikes sit around the +z axis.
    private val positiveCluster = List(10) { unit(1f, 0.01f * it, 0f) }
    private val negativeCluster = List(10) { unit(0f, 0.01f * it, 1f) }
    private val positiveCentroids = KMeans.centroids(positiveCluster, k = 3)

    @Test
    fun `with no negatives the score is identical to positive-only similarity`() {
        val probe = unit(1f, 0f, 0f)
        val expected = KMeans.similarityToNearest(probe, positiveCentroids)
        // alpha = 1.0, gamma * 0 = 0, and expected is in [0,1] so the clamp is identity — bit-identical to today.
        assertEquals(expected, RocchioRanker.score(probe, positiveCentroids, negativeCentroid = null), 1e-12)
    }

    @Test
    fun `a paper resembling dismissed papers scores below one resembling saves`() {
        val negativeCentroid = RocchioRanker.negativeCentroid(negativeCluster)
        val likeSaved = RocchioRanker.score(unit(1f, 0f, 0f), positiveCentroids, negativeCentroid)
        val likeDismissed = RocchioRanker.score(unit(0f, 0f, 1f), positiveCentroids, negativeCentroid)

        assertTrue(likeSaved > likeDismissed, "a save-like paper outranks a dismiss-like paper")
        assertTrue(likeSaved > 0.55, "the save-like paper clears the 'Likely relevant' threshold")
        assertTrue(likeDismissed < 0.55, "the dismiss-like paper is pushed out of 'Likely relevant'")
    }

    @Test
    fun `score is always clamped to the unit interval`() {
        val negativeCentroid = RocchioRanker.negativeCentroid(negativeCluster)
        val probes = listOf(unit(1f, 0f, 0f), unit(0f, 0f, 1f), unit(0f, 1f, 0f), unit(-1f, 0f, 0f))
        probes.forEach { probe ->
            val score = RocchioRanker.score(probe, positiveCentroids, negativeCentroid)
            assertTrue(score in 0.0..1.0, "score $score is within [0,1]")
        }
    }

    @Test
    fun `fewer than the minimum negatives yields no negative centroid`() {
        assertNull(RocchioRanker.negativeCentroid(negativeCluster.take(2)))
        assertTrue(RocchioRanker.negativeCentroid(negativeCluster.take(3)) != null)
    }

    @Test
    fun `deterministic across runs`() {
        val negativeCentroid = RocchioRanker.negativeCentroid(negativeCluster)
        val probe = unit(1f, 0.3f, 0.2f)
        val first = RocchioRanker.score(probe, KMeans.centroids(positiveCluster, k = 3), negativeCentroid)
        val second = RocchioRanker.score(probe, KMeans.centroids(positiveCluster, k = 3), negativeCentroid)
        assertEquals(first, second)
    }
}
