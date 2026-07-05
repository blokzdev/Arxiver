package dev.blokz.arxiver.core.database.fts

import dev.blokz.arxiver.core.database.dao.SearchDao
import dev.blokz.arxiver.core.database.entity.PaperEntity

data class KeywordHit(
    val paper: PaperEntity,
    val score: Double,
)

/**
 * Local keyword search (SPEC-DATA §3): paper-field and note matches scored by
 * weighted BM25, merged per paper (scores add — a paper matching in both its
 * abstract and a note outranks either alone), best first.
 */
class LocalKeywordSearch(private val searchDao: SearchDao) {
    suspend fun search(
        rawQuery: String,
        limit: Int = 30,
        // P-Tools PT.1 red line: default true keeps every existing caller byte-identical, but a
        // cloud-tool library search passes false so private note text never influences the ranking
        // that returns to the model.
        includeNotes: Boolean = true,
    ): List<KeywordHit> {
        val match = buildMatchQuery(rawQuery)
        if (match.isBlank()) return emptyList()

        val scores = mutableMapOf<String, Double>()
        searchDao.matchPapers(match).forEach {
            scores.merge(it.paperId, Bm25.score(it.matchinfo, Bm25.PAPER_WEIGHTS), Double::plus)
        }
        if (includeNotes) {
            searchDao.matchNotes(match).forEach {
                scores.merge(it.paperId, Bm25.score(it.matchinfo, Bm25.NOTE_WEIGHTS), Double::plus)
            }
        }
        if (scores.isEmpty()) return emptyList()

        val top = scores.entries.sortedByDescending { it.value }.take(limit)
        val papers = searchDao.papersByIds(top.map { it.key }).associateBy { it.id }
        return top.mapNotNull { (id, score) ->
            papers[id]?.let { KeywordHit(paper = it, score = score) }
        }
    }
}
