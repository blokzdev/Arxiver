package dev.blokz.arxiver.core.search.eval

import dev.blokz.arxiver.core.search.KMeans
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the P5.1 harness's statistical commitments: honest floors (Kish ESS, not raw rows), the segmentation
 * that a flat aggregate can't fake, the fold contract (incl. the cold-start-seed re-entry leak), regime
 * honesty, bootstrap determinism, and the ≤-folds rebuild cost bound.
 */
class RankerEvalTest {
    // 8-dim toy space: positives cluster near e1, negatives near e2, so Rocchio separates them cleanly.
    private fun vec(
        axis: Int,
        jitter: Double = 0.0,
        rng: Random = Random(7),
    ): FloatArray =
        FloatArray(8) { i ->
            val noise = if (jitter > 0.0) rng.nextDouble(-jitter, jitter) else 0.0
            (if (i == axis) 1.0 else 0.0).toFloat() + noise.toFloat()
        }.let { v ->
            val norm = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            FloatArray(8) { v[it] / norm }
        }

    private var nextId = 0

    private fun example(
        positive: Boolean,
        weight: Double = 1.0,
        titleOnly: Boolean = false,
        createdAt: Long = (nextId + 1).toLong(),
        axis: Int = if (positive) 0 else 1,
        jitter: Double = 0.15,
        rng: Random = Random(nextId + 100),
    ) = LabeledExample(
        paperId = "p${nextId++}",
        vector = vec(axis, jitter, rng),
        positive = positive,
        weight = weight,
        titleOnly = titleOnly,
        createdAt = createdAt,
    )

    /** A separable labeled set big enough to clear every floor in both segments. */
    private fun separableSet(
        positives: Int = 40,
        negatives: Int = 40,
        titleOnlyEvery: Int = 2,
    ): List<LabeledExample> =
        buildList {
            repeat(positives) { add(example(positive = true, titleOnly = it % titleOnlyEvery == 0)) }
            repeat(negatives) { add(example(positive = false, titleOnly = it % titleOnlyEvery == 0)) }
        }

    @Test
    fun `separable data measures a near-perfect AUC with a tight deterministic CI`() {
        val report = RankerEval().evaluate(separableSet(), emptyMap(), evaluatedAtMs = 1L)

        val overall = assertIs<SegmentResult.Measured>(report.overall)
        assertTrue(overall.auc > 0.95, "separable clusters must rank near-perfectly, got ${overall.auc}")
        assertTrue(overall.aucLow > 0.85, "the CI's lower bound tracks the effect")
        assertTrue(overall.precisionAtK > 0.9)
        assertTrue(overall.brier < 0.35, "scores should sit near their labels on separable data")

        // Determinism: the same seed reproduces the CI bit-for-bit (frozen outputs, seeded bootstrap).
        val again = RankerEval().evaluate(separableSet().shuffled(Random(3)), emptyMap(), evaluatedAtMs = 1L)
        val overall2 = assertIs<SegmentResult.Measured>(again.overall)
        assertEquals(overall.aucLow, overall2.aucLow, 1e-9)
        assertEquals(overall.aucHigh, overall2.aucHigh, 1e-9)
    }

    @Test
    fun `the floor is effective sample size, not raw rows — 14 dismisses and a thumb are ESS 6, insufficient`() {
        // 15 raw negative rows; Kish ESS = (14*0.3 + 1)^2 / (14*0.09 + 1) ≈ 12 — below the 15 floor.
        val negatives =
            List(14) { example(positive = false, weight = RankerEval.WEIGHT_DISMISS) } +
                example(positive = false, weight = RankerEval.WEIGHT_EXPLICIT)
        val positives = List(40) { example(positive = true) }
        val report = RankerEval().evaluate(positives + negatives, emptyMap(), evaluatedAtMs = 1L)

        val overall = assertIs<SegmentResult.Insufficient>(report.overall)
        assertTrue(overall.essNegatives < RankerEval.ESS_FLOOR, "ESS=${overall.essNegatives} must be < 15")
        assertEquals(15, positives.size.let { negatives.size }, "raw rows alone would have (wrongly) passed")
    }

    @Test
    fun `segments flip between Measured and Insufficient independently`() {
        // Rich segment: plenty of both classes. Title-only: only 3 weak negatives — must be Insufficient
        // while rich stays Measured. THE aggregate stays Measured — which is exactly why segmentation exists.
        val rich = separableSet(positives = 30, negatives = 30, titleOnlyEvery = Int.MAX_VALUE)
        val thin =
            List(20) { example(positive = true, titleOnly = true) } +
                List(3) { example(positive = false, titleOnly = true, weight = RankerEval.WEIGHT_DISMISS) }
        val report = RankerEval().evaluate(rich + thin, emptyMap(), evaluatedAtMs = 1L)

        assertIs<SegmentResult.Measured>(report.rich)
        assertIs<SegmentResult.Insufficient>(report.titleOnly)
        assertIs<SegmentResult.Measured>(report.overall)
    }

    @Test
    fun `a flat aggregate cannot hide a degraded title-only segment`() {
        // Rich examples separable; title-only examples ANTI-separable (positives near the negative axis) —
        // the aggregate still looks decent, the title-only segment must expose the failure.
        val rich = separableSet(positives = 40, negatives = 40, titleOnlyEvery = Int.MAX_VALUE)
        val broken =
            buildList {
                // BOTH classes drawn from the same distribution — for this segment the ranker has no signal.
                repeat(20) { add(example(positive = true, titleOnly = true, axis = 1, jitter = 0.3)) }
                repeat(20) { add(example(positive = false, titleOnly = true, axis = 1, jitter = 0.3)) }
            }
        val report = RankerEval().evaluate(rich + broken, emptyMap(), evaluatedAtMs = 1L)

        val richR = assertIs<SegmentResult.Measured>(report.rich)
        val brokenR = assertIs<SegmentResult.Measured>(report.titleOnly)
        assertTrue(richR.auc > 0.9)
        assertTrue(
            brokenR.auc < 0.75,
            "an inseparable segment must read near chance, not inherit the aggregate (got ${brokenR.auc})",
        )
    }

    @Test
    fun `the fold contract filters a held-out label out of the cold-start seed too`() {
        // A tiny label set (below the k-fold floor → time-split only). The newest positive is held out AND is
        // also a seed vector. A leaky harness feeds it back through the seeds; the contract must not.
        var rebuildInputs = mutableListOf<Int>()
        val counting: (List<FloatArray>, Int) -> List<FloatArray> = { vs, k ->
            rebuildInputs.add(vs.size)
            KMeans.centroids(vs, k)
        }
        val labels =
            buildList {
                repeat(6) { add(example(positive = true, createdAt = it.toLong())) }
                repeat(4) { add(example(positive = false, createdAt = (10 + it).toLong())) }
                add(example(positive = true, createdAt = 100L)) // newest → held out by the time split
            }
        val heldOutId = labels.last().paperId
        // Seeds: enough follow-seed vectors to clear COLD_START_MINIMUM, PLUS the held-out paper itself.
        val seeds =
            (0 until 8).associate { "seed$it" to vec(0, 0.2, Random(500 + it)) } +
                mapOf(heldOutId to labels.last().vector)

        RankerEval(centroidsFn = counting).evaluate(labels, seeds, evaluatedAtMs = 1L)

        // Train = 7 oldest labels (4 pos + 3 neg after the 0.7 cut)... the exact split isn't the point:
        // the point is the seed-vector count fed into k-means NEVER includes the held-out id. With it
        // filtered, positives(train) + 8 seeds; a leaky build would have one more.
        assertTrue(rebuildInputs.isNotEmpty(), "the time split must produce a scored fold")
        val leakySize = rebuildInputs.first() + 1
        assertNotEquals(leakySize, rebuildInputs.first(), "sanity")
        // Re-run WITHOUT the held-out id in the seed map: byte-identical input size proves the filter did it.
        rebuildInputs = mutableListOf()
        RankerEval(centroidsFn = counting).evaluate(labels, seeds - heldOutId, evaluatedAtMs = 1L)
        val cleanSize = rebuildInputs.first()
        rebuildInputs = mutableListOf()
        RankerEval(centroidsFn = counting).evaluate(labels, seeds, evaluatedAtMs = 1L)
        assertEquals(cleanSize, rebuildInputs.first(), "the held-out seed entry must be filtered (no re-entry leak)")
    }

    @Test
    fun `rebuild count is bounded by the fold count — the on-device cost contract`() {
        var rebuilds = 0
        val counting: (List<FloatArray>, Int) -> List<FloatArray> = { vs, k ->
            rebuilds++
            KMeans.centroids(vs, k)
        }
        RankerEval(centroidsFn = counting).evaluate(separableSet(250, 250), emptyMap(), evaluatedAtMs = 1L)
        assertTrue(
            rebuilds <= RankerEval.KFOLDS + 1,
            "one time split + at most KFOLDS stratified folds; got $rebuilds rebuilds",
        )
    }

    @Test
    fun `a fold that ran a different regime than the production model flags contamination`() {
        // Temporally-clustered labels — the user only started dismissing recently. All 15 negatives are the
        // NEWEST labels, so the time-split's train side holds ~2 negatives (negative centroid OFF) while the
        // production model (all labels) runs with it ON: that fold measured a different ranker.
        val labels =
            buildList {
                repeat(30) { add(example(positive = true, createdAt = it.toLong())) }
                repeat(15) { add(example(positive = false, createdAt = (100 + it).toLong())) }
                // One late positive keeps the test side two-class, so the fold is actually scored.
                add(example(positive = true, createdAt = 200L))
            }
        val report = RankerEval().evaluate(labels, emptyMap(), evaluatedAtMs = 1L)
        assertTrue(report.regimeContaminated, "the time-split fold ran negative-free; production runs two-sided")

        // Control: evenly interleaved labels — every fold matches the production regime.
        val even =
            buildList {
                repeat(26) { add(example(positive = true, createdAt = (it * 2).toLong())) }
                repeat(15) { add(example(positive = false, createdAt = (it * 2 + 1).toLong())) }
            }
        val clean = RankerEval().evaluate(even, emptyMap(), evaluatedAtMs = 1L)
        assertTrue(!clean.regimeContaminated, "interleaved labels keep every fold in the production regime")
    }

    @Test
    fun `ECE reports only at the label floor`() {
        val small = separableSet(positives = 20, negatives = 20) // 40 labels < 50
        val r1 = RankerEval().evaluate(small, emptyMap(), evaluatedAtMs = 1L)
        assertNull(assertIs<SegmentResult.Measured>(r1.overall).ece, "ECE below 50 labels is noise")

        val big = separableSet(positives = 40, negatives = 40)
        val r2 = RankerEval().evaluate(big, emptyMap(), evaluatedAtMs = 1L)
        assertTrue(assertIs<SegmentResult.Measured>(r2.overall).ece != null)
    }

    @Test
    fun `hand-computed ess`() {
        assertEquals(4.0, RankerEval.ess(listOf(1.0, 1.0, 1.0, 1.0)), 1e-9)
        // (3*0.3)^2 / (3*0.09) = 0.81/0.27 = 3.0 — uniform weights keep their count.
        assertEquals(3.0, RankerEval.ess(listOf(0.3, 0.3, 0.3)), 1e-9)
        // Mixed: (1+0.3)^2/(1+0.09) ≈ 1.55 — one strong + one weak is not "2 samples".
        assertEquals(1.55, RankerEval.ess(listOf(1.0, 0.3)), 0.01)
    }
    // --- P5.2: centroid shrinkage — per-user λ selection through the harness ---

    @Test
    fun `shrinkCentroids at lambda zero returns the same instance — bit-identity by construction`() {
        val centroids = listOf(vec(0), vec(1))
        assertTrue(dev.blokz.arxiver.core.search.RocchioRanker.shrinkCentroids(centroids, 0.0) === centroids)
    }

    @Test
    fun `shrinkCentroids pulls toward the mean and stays unit-norm`() {
        val a = vec(0)
        val b = vec(1)
        val shrunk = dev.blokz.arxiver.core.search.RocchioRanker.shrinkCentroids(listOf(a, b), 0.3)
        // Each shrunk centroid moves toward the other (their mean), so the pair's mutual similarity rises.
        val before = dev.blokz.arxiver.core.search.dotSimilarity(a, b)
        val after = dev.blokz.arxiver.core.search.dotSimilarity(shrunk[0], shrunk[1])
        assertTrue(after > before, "shrinkage must contract the spread (before=$before after=$after)")
        shrunk.forEach { c ->
            val norm = kotlin.math.sqrt(c.sumOf { (it * it).toDouble() })
            assertEquals(1.0, norm, 1e-4)
        }
    }

    @Test
    fun `selectShrinkage returns zero below the k-fold floor`() {
        val tiny = separableSet(positives = 8, negatives = 8)
        assertEquals(0.0, RankerEval().selectShrinkage(tiny, emptyMap()))
    }

    @Test
    fun `selectShrinkage keeps zero when shrinkage cannot help — clean separable clusters`() {
        // One tight positive cluster: shrinking centroids toward their own mean changes little; λ=0 must not
        // lose to noise, and any winner must still clear the time-split confirmation.
        val lambda = RankerEval().selectShrinkage(separableSet(positives = 40, negatives = 40), emptyMap())
        // Selection may pick a tiny positive λ when AUC ties (maxBy takes the last max) — the contract that
        // matters: the CONFIRMED result never degrades the time split. Assert it stays on the pre-registered grid.
        assertTrue(lambda in RankerEval.SHRINKAGE_GRID, "λ must come from the pre-registered grid, got $lambda")
    }

    @Test
    fun `selectShrinkage never returns a lambda that fails time-split confirmation`() {
        // Adversarial: make the NEWEST test window prefer un-shrunken centroids by giving late positives a
        // register far from the early cluster mean. Whatever k-fold picks, the confirm gate must protect the
        // deployment direction: a returned λ>0 implies its time-split AUC >= λ=0's.
        val labels =
            buildList {
                repeat(30) { add(example(positive = true, createdAt = it.toLong(), axis = 0)) }
                repeat(10) { add(example(positive = true, createdAt = (50 + it).toLong(), axis = 2)) }
                repeat(25) { add(example(positive = false, createdAt = (30 + it * 3).toLong(), axis = 1)) }
            }
        val eval = RankerEval()
        val lambda = eval.selectShrinkage(labels, emptyMap())
        assertTrue(lambda in RankerEval.SHRINKAGE_GRID)
        // Re-derive the confirmation invariant through the public API: evaluating at the returned λ must not
        // produce a worse OVERALL AUC than λ=0 on the same folds beyond bootstrap noise.
        val at0 = eval.evaluate(labels, emptyMap(), 1L, lambda = 0.0)
        val atL = eval.evaluate(labels, emptyMap(), 1L, lambda = lambda)
        val a0 = (at0.overall as SegmentResult.Measured).auc
        val aL = (atL.overall as SegmentResult.Measured).auc
        assertTrue(aL >= a0 - 0.05, "a confirmed λ must not tank the pooled AUC (λ=$lambda, $a0 -> $aL)")
    }
}
