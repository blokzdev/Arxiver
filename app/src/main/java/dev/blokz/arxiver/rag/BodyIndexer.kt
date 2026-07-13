package dev.blokz.arxiver.rag

import dev.blokz.arxiver.core.ai.BodyTextExtractor
import dev.blokz.arxiver.core.ai.HtmlStorage
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.model.ArxivId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The minimal seam the HTML reader uses to nudge body-indexing when a paper is opened (P-FullText PFT.2) —
 * decoupled from the full [BodyIndexer] so the reader ViewModel (and its test) depends only on this.
 */
fun interface BodyIndexTrigger {
    suspend fun indexOnOpen(
        id: ArxivId,
        version: Int,
    )
}

/**
 * Populates `source_kind = body` chunks from the persisted reader HTML (P-FullText PFT.2). Two entry points:
 * [indexOnOpen] — an expedited, fire-and-forget nudge when a paper is opened in the reader — and [backfill],
 * the filesystem-driven bounded worker catch-all. Both short-circuit on the `.bodyindex` sidecar so an
 * already-current (version + model) body is never re-extracted/re-embedded, and both serialize per paper so
 * the reader-open trigger and the worker can never tear each other's scoped re-index. Fully on-device: the
 * body was already downloaded from an allowlisted arXiv host; extraction is local and adds zero egress.
 */
class BodyIndexer(
    private val htmlStorage: HtmlStorage,
    private val extractor: BodyTextExtractor,
    private val ragIndexer: RagIndexer,
    private val dispatchers: DispatcherProvider,
    private val modelName: String,
) : BodyIndexTrigger {
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Index the opened paper's body if not already current (version + model). Cheap no-op when current. */
    override suspend fun indexOnOpen(
        id: ArxivId,
        version: Int,
    ) {
        if (htmlStorage.readBodyIndex(id, version) == modelName) return
        locks.getOrPut(id.value) { Mutex() }.withLock {
            // Re-check inside the lock: a racing trigger/backfill may have just indexed it.
            if (htmlStorage.readBodyIndex(id, version) == modelName) return
            val body = withContext(dispatchers.io) { htmlStorage.localHtml(id, version)?.file?.readText() } ?: return
            val text = extractor.extract(body)
            when (ragIndexer.indexPaperBody(id.value, text)) {
                // Stamp on success even for empty text (a math-only body is genuinely done, not retryable).
                is AppResult.Success -> htmlStorage.storeBodyIndex(id, version, modelName)
                is AppResult.Failure -> Unit // leave unstamped → retried on the next open / worker pass
            }
        }
    }

    /**
     * Bounded backfill of cached bodies missing or stale a current-model body index (the worker catch-all).
     * A `MODEL_NAME` bump self-heals here: the model-mismatch wipe already dropped the old body chunks, and
     * the stale sidecar model makes each affected body a candidate again.
     */
    suspend fun backfill(
        limit: Int,
        shouldStop: () -> Boolean = { false },
    ) {
        val stale = htmlStorage.cachedBodies().filter { it.indexedModel != modelName }.take(limit)
        for (ref in stale) {
            if (shouldStop()) return
            indexOnOpen(ref.id, ref.version)
        }
    }
}
