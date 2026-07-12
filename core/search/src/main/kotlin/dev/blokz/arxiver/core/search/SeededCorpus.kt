package dev.blokz.arxiver.core.search

import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity
import java.util.Random

/**
 * A deterministic synthetic embedding corpus for the perf-regression guards (P-Prove PP.1) and — later — the
 * on-device Macrobenchmark seeding (PP.3). ONE generator so the CI complexity/allocation tripwire and the device
 * benchmark exercise byte-identical inputs. Pure + fixed-seed ⇒ reproducible. `internal`: test/benchmark support
 * only; it is never on a production call path (the release seeding hook is `BuildConfig`-gated off).
 */
internal object SeededCorpus {
    /** bge-small-en-v1.5 embedding width (the shipped model). */
    const val DIM = 384
    const val MODEL = "bge-small-en-v1.5-q8"

    /**
     * [n] L2-normalized [dim]-d embeddings drawn i.i.d. from a fixed [seed], wrapped as [PaperEmbeddingEntity]
     * with ids "p000000"…. Their similarities to a random [queryVector] are distinct (continuous draws), so a
     * top-k is unambiguous and tie-free — deterministic under the seed, no engineered bias needed.
     */
    fun embeddings(
        n: Int,
        seed: Long = 42L,
        dim: Int = DIM,
    ): List<PaperEmbeddingEntity> {
        val rng = Random(seed)
        return (0 until n).map { i ->
            val v = FloatArray(dim) { rng.nextGaussian().toFloat() }.l2Normalized()
            PaperEmbeddingEntity(
                paperId = "p%06d".format(i),
                vector = PaperEmbeddingEntity.floatsToBlob(v),
                model = MODEL,
                dim = dim,
            )
        }
    }

    /** A fixed random unit query (a distinct [seed] from the corpus) ⇒ deterministic, tie-free ranking. */
    fun queryVector(
        seed: Long = 7L,
        dim: Int = DIM,
    ): FloatArray {
        val rng = Random(seed)
        return FloatArray(dim) { rng.nextGaussian().toFloat() }.l2Normalized()
    }
}
