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
    ): List<KeywordHit> {
        val match = buildMatchQuery(rawQuery)
        if (match.isBlank()) return emptyList()

        val scores = mutableMapOf<String, Double>()
        searchDao.matchPapers(match).forEach {
            scores.merge(it.paperId, Bm25.score(it.matchinfo, Bm25.PAPER_WEIGHTS), Double::plus)
        }
        searchDao.matchNotes(match).forEach {
            scores.merge(it.paperId, Bm25.score(it.matchinfo, Bm25.NOTE_WEIGHTS), Double::plus)
        }
        if (scores.isEmpty()) return emptyList()

        val top = scores.entries.sortedByDescending { it.value }.take(limit)
        val papers = searchDao.papersByIds(top.map { it.key }).associateBy { it.id }
        return top.mapNotNull { (id, score) ->
            papers[id]?.let { KeywordHit(paper = it, score = score) }
        }
    }
}
