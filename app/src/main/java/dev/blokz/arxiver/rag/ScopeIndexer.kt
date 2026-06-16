package dev.blokz.arxiver.rag

import dev.blokz.arxiver.core.search.RetrievalScope

/**
 * Ensures a retrieval scope's papers are chunk-embedded before chat (P2.4). A
 * narrow seam over [RagIndexer] so the chat ViewModel stays ONNX-free in tests:
 * DI binds `Collection` → `RagIndexer.indexCollection`, `Paper` → no-op (library
 * papers are covered by the background backfill).
 */
fun interface ScopeIndexer {
    suspend fun ensureIndexed(scope: RetrievalScope)
}
