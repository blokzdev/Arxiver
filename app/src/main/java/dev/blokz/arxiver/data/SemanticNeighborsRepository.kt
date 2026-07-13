package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.dao.EmbeddingDao
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity
import dev.blokz.arxiver.core.database.toListDomain
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.search.VectorIndex
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A paper semantically similar to the one being viewed (cosine over on-device embeddings). */
data class RelatedPaper(
    val paper: Paper,
    val similarity: Double,
)

/**
 * Outcome of resolving a paper's "more like this" neighbors (P-Discover2 PD.2) — an honest typed cause, not a bare
 * empty list, so the UI can distinguish "still indexing" from "no similar papers on device yet".
 */
sealed interface NeighborsResult {
    data object Loading : NeighborsResult

    data class Ready(val neighbors: List<RelatedPaper>) : NeighborsResult

    /** The paper has no embedding yet (not indexed), so neighbors can't be computed. */
    data object NotEmbedded : NeighborsResult

    /** Embedded, but nothing on device clears the similarity floor. */
    data object NoRelations : NeighborsResult
}

/**
 * On-device "more like this" for ANY paper (P-Discover2 PD.2) — network-free. The precomputed `related_papers` path
 * (read reactively by the ViewModel) exists only for worker-processed library papers; [liveNeighborsFor] is the live
 * cosine fallback so a search/browse-opened paper still maps. Mirrors `RelationGraphRepository`'s neighbor pattern,
 * without the citation edges.
 */
class SemanticNeighborsRepository
    @Inject
    constructor(
        private val paperDao: PaperDao,
        private val embeddingDao: EmbeddingDao,
        private val dispatchers: DispatcherProvider,
    ) {
        private val vectorIndex = VectorIndex(embeddingDao)

        /**
         * A live top-K cosine scan for [paperId] — used only when the precomputed neighbors are empty.
         * [NeighborsResult.NotEmbedded] if the paper has no embedding, [NeighborsResult.NoRelations] if nothing clears
         * [SIMILARITY_FLOOR], else [NeighborsResult.Ready].
         */
        suspend fun liveNeighborsFor(paperId: String): NeighborsResult =
            withContext(dispatchers.io) {
                val embedding = embeddingDao.byPaperId(paperId) ?: return@withContext NeighborsResult.NotEmbedded
                val vector = PaperEmbeddingEntity.blobToFloats(embedding.vector)
                val hits =
                    vectorIndex.topK(vector, MAX_NEIGHBORS, excludeId = paperId)
                        .filter { it.similarity >= SIMILARITY_FLOOR }
                        .mapNotNull { hit ->
                            paperDao.paperById(hit.paperId)?.let { RelatedPaper(it.toListDomain(), hit.similarity) }
                        }
                if (hits.isEmpty()) NeighborsResult.NoRelations else NeighborsResult.Ready(hits)
            }

        companion object {
            const val MAX_NEIGHBORS = 6
            const val SIMILARITY_FLOOR = 0.3
        }
    }
