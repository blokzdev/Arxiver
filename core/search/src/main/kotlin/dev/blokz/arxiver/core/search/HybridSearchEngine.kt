package dev.blokz.arxiver.core.search

/** Where a hybrid result came from (SPEC-SEARCH §3 provenance badges). */
enum class Provenance { KEYWORD, SEMANTIC, BOTH }

data class HybridHit(
    val paperId: String,
    val score: Double,
    val provenance: Provenance,
)

/** Tunables in one place (SPEC-SEARCH §3). */
data class SearchTuning(
    val keywordWeight: Double = 0.25,
    val semanticWeight: Double = 0.75,
    val qualityGate: Double = 0.70,
    val legLimit: Int = 30,
    val resultLimit: Int = 20,
)

/**
 * Score fusion per SPEC-SEARCH §3 — pure function, independently testable:
 * min-max normalize each leg, weight 25/75, dedupe, gate at 70% of best.
 * A paper appearing in only one leg keeps its weighted score.
 */
object HybridFusion {
    fun fuse(
        keyword: List<Pair<String, Double>>,
        semantic: List<Pair<String, Double>>,
        tuning: SearchTuning = SearchTuning(),
    ): List<HybridHit> {
        val kw = normalize(keyword.take(tuning.legLimit))
        val sem = normalize(semantic.take(tuning.legLimit))

        val merged = mutableMapOf<String, HybridHit>()
        kw.forEach { (id, score) ->
            merged[id] = HybridHit(id, score * tuning.keywordWeight, Provenance.KEYWORD)
        }
        sem.forEach { (id, score) ->
            val existing = merged[id]
            merged[id] =
                if (existing == null) {
                    HybridHit(id, score * tuning.semanticWeight, Provenance.SEMANTIC)
                } else {
                    HybridHit(id, existing.score + score * tuning.semanticWeight, Provenance.BOTH)
                }
        }
        if (merged.isEmpty()) return emptyList()

        val best = merged.values.maxOf { it.score }
        return merged.values
            .filter { it.score >= best * tuning.qualityGate }
            .sortedByDescending { it.score }
            .take(tuning.resultLimit)
    }

    /** Min-max to [0,1]; a single-element leg maps to 1.0. */
    private fun normalize(leg: List<Pair<String, Double>>): List<Pair<String, Double>> {
        if (leg.isEmpty()) return leg
        val min = leg.minOf { it.second }
        val max = leg.maxOf { it.second }
        val range = max - min
        return leg.map { (id, score) ->
            id to if (range == 0.0) 1.0 else (score - min) / range
        }
    }
}
