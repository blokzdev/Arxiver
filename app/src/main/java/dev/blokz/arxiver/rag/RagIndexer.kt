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
 * in DI; faked in tests so no ONNX is needed), and replaces the paper's chunk rows
 * (delete-then-insert keeps `(paper_id, source_kind, ordinal)` unique and re-index
 * idempotent). Embedding is the only expensive step and stays fully on-device.
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
            chunkDao.deleteForPaper(paperId)
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
            chunkDao.deleteForPaper(paperId)
            chunkDao.insert(rows)
        }
    }
}
