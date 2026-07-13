package dev.blokz.arxiver.rag

import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.map
import dev.blokz.arxiver.core.database.dao.ChunkEmbeddingDao
import dev.blokz.arxiver.core.database.dao.LibraryDao
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.database.entity.ChunkEmbeddingEntity
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity
import dev.blokz.arxiver.core.search.TextChunker
import java.time.Instant

/**
 * Indexes a paper's text into the chunk-embedding store for RAG retrieval
 * (SPEC-SEARCH §8). Chunks `title + abstract + notes` via [TextChunker], embeds
 * each chunk with the injected [embed] seam (bound to `EmbeddingService::embedPassages`
 * in DI; faked in tests so no ONNX is needed), and replaces the paper's **abstract+note**
 * chunk rows (source-scoped delete-then-insert in one transaction — keeps
 * `(paper_id, source_kind, ordinal)` unique, re-index idempotent, and never clobbers a
 * paper's `body` chunks (P-FullText PFT.1)). Embedding is the only expensive step and
 * stays fully on-device.
 */
class RagIndexer(
    private val paperDao: PaperDao,
    private val libraryDao: LibraryDao,
    private val chunkDao: ChunkEmbeddingDao,
    private val chunker: TextChunker,
    private val modelName: String,
    private val embed: suspend (List<String>) -> AppResult<List<FloatArray>>,
    private val clock: () -> Long = { Instant.now().toEpochMilli() },
) {
    /** Re-chunk and re-embed [paperId]. A no-op success if the paper is unknown or has no text. */
    suspend fun indexPaper(paperId: String): AppResult<Unit> {
        val paper = paperDao.paperById(paperId) ?: return AppResult.Success(Unit)
        val notes = libraryDao.notesFor(paperId).map { it.content }
        val chunks = chunker.chunk(paper.title, paper.abstract, notes)
        if (chunks.isEmpty()) {
            chunkDao.deleteForPaperSources(paperId, ABSTRACT_NOTE)
            return AppResult.Success(Unit)
        }
        return embed(chunks.map { it.text }).map { vectors ->
            val now = clock()
            val rows =
                chunks.zip(vectors).map { (chunk, vector) ->
                    ChunkEmbeddingEntity(
                        paperId = paperId,
                        chunkText = chunk.text,
                        vector = PaperEmbeddingEntity.floatsToBlob(vector),
                        model = modelName,
                        dim = vector.size,
                        sourceKind = chunk.sourceKind,
                        ordinal = chunk.ordinal,
                        embeddedAt = now,
                    )
                }
            chunkDao.replacePaperSources(paperId, ABSTRACT_NOTE, rows)
        }
    }

    /**
     * Index a paper's full-body text as `source_kind = body` chunks (P-FullText PFT.2). Source-scoped
     * (never touches abstract/note chunks), model-guarded, and idempotent via [replacePaperSources]. Empty
     * body text clears any stale body chunks and still succeeds — an all-math/figure body is honestly "no
     * indexable text" (it just earns no full-text coverage), never an error.
     */
    suspend fun indexPaperBody(
        paperId: String,
        bodyText: String,
    ): AppResult<Unit> {
        // A cached reader body can outlive its paper row (the HTML cache is filesystem, not FK-coupled), so a
        // gone paper is an indexed no-op — never FK-fail the chunk insert (which, in a backfill loop, would
        // abort the whole batch on one orphan).
        if (paperDao.paperById(paperId) == null) return AppResult.Success(Unit)
        val chunks = chunker.chunkBody(bodyText)
        if (chunks.isEmpty()) {
            chunkDao.deleteForPaperSources(paperId, BODY)
            return AppResult.Success(Unit)
        }
        return embed(chunks.map { it.text }).map { vectors ->
            val now = clock()
            val rows =
                chunks.zip(vectors).map { (chunk, vector) ->
                    ChunkEmbeddingEntity(
                        paperId = paperId,
                        chunkText = chunk.text,
                        vector = PaperEmbeddingEntity.floatsToBlob(vector),
                        model = modelName,
                        dim = vector.size,
                        sourceKind = chunk.sourceKind,
                        ordinal = chunk.ordinal,
                        embeddedAt = now,
                    )
                }
            chunkDao.replacePaperSources(paperId, BODY, rows)
        }
    }

    /**
     * Ensure a collection's papers are chunk-embedded for KB chat (P2.4): indexes
     * only papers missing current-model chunks (already-indexed papers — e.g. from
     * the library backfill — are skipped, so this is cheap on a warm collection).
     * Bounded per call; a very large collection finishes over subsequent opens.
     */
    suspend fun indexCollection(
        collectionId: Long,
        limit: Int = COLLECTION_BATCH,
    ): AppResult<Unit> {
        val pending = chunkDao.collectionPapersMissingChunks(collectionId, modelName, limit)
        for (paperId in pending) {
            when (val result = indexPaper(paperId)) {
                is AppResult.Failure -> return result
                is AppResult.Success -> Unit
            }
        }
        return AppResult.Success(Unit)
    }

    companion object {
        private const val COLLECTION_BATCH = 100

        /**
         * [indexPaper] owns the abstract+note sources only; body chunks (PFT.2) are maintained by a separate
         * pass, so its scoped delete/replace targets exactly these and leaves any `body` rows untouched.
         */
        private val ABSTRACT_NOTE =
            listOf(ChunkEmbeddingEntity.SOURCE_ABSTRACT, ChunkEmbeddingEntity.SOURCE_NOTE)

        /** [indexPaperBody] owns exactly the body source — its scoped delete/replace never touches abstract/note. */
        private val BODY = listOf(ChunkEmbeddingEntity.SOURCE_BODY)
    }
}
