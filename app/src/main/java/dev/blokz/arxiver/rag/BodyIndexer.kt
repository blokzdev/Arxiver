package dev.blokz.arxiver.rag

import dev.blokz.arxiver.core.ai.BodyTextExtractor
import dev.blokz.arxiver.core.ai.HtmlStorage
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.PaperRef
import dev.blokz.arxiver.core.pdf.PdfTextQualityGate
import dev.blokz.arxiver.data.PdfBodyStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
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
 * The PDF-viewer twin of [BodyIndexTrigger] (Phase P-Reader2 PFT.5.5): nudges body-indexing from a
 * **downloaded PDF** when the viewer opens a paper. `storageId` (not an [ArxivId]) because the PDF path also
 * covers non-arXiv papers (chemRxiv/bioRxiv), which have no arXiv HTML edition.
 */
fun interface PdfBodyIndexTrigger {
    suspend fun indexPdfOnOpen(
        storageId: String,
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
    /** Extraction seam `File -> body text` (production binds `PdfBodyTextExtractor::extract`); injectable so a
     *  unit test drives the arbitration/gate/marker logic without real pdfbox in the module's Robolectric. */
    private val pdfExtract: suspend (File) -> String,
    private val pdfBodyStore: PdfBodyStore,
    /** Gate seam — injectable so a unit test can force accept/reject; production is the real pure gate. */
    private val isAcceptablePdfText: (String) -> Boolean = PdfTextQualityGate::isAcceptable,
    private val gateVersion: Int = PdfTextQualityGate.GATE_VERSION,
) : BodyIndexTrigger, PdfBodyIndexTrigger {
    // Keyed by storageId. For an arXiv paper storageId == ArxivId.value, so [indexOnOpen] and [indexPdf] take
    // the SAME lock — that mutual exclusion is what makes the inside-lock HTML-defer re-check in [indexPdf]
    // sound: a PDF index can never run while an HTML index of the same paper is mid-flight, and vice versa.
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

    // --- P-Reader2 PFT.5.5: the PDF body path (HTML stays preferred-per-paper; PDF is the fallback) ---

    /** Nudge on PDF-viewer open. Records the authoritative `.pdf.id` sidecar (so the backfill can recover the
     *  storageId from the filesystem) then indexes. Fire-and-forget by the caller; a no-op when already current. */
    override suspend fun indexPdfOnOpen(
        storageId: String,
        version: Int,
    ) {
        pdfBodyStore.storeId(storageId, version)
        indexPdf(storageId, version)
    }

    /**
     * Index a downloaded PDF's body unless (a) already current (`OK:<model>` / `SKIP:<gateVersion>`), or (b) the
     * paper's HTML edition is already body-indexed with the current model — the **HTML-preferred arbitration**,
     * re-checked INSIDE the shared per-storageId lock so a PDF fallback can never clobber a cleaner HTML body
     * that committed between the trigger firing and this path acquiring the lock (the TOCTOU clobber race).
     * Garbage (gate-rejected) drops any body chunks and stamps `SKIP:<gateVersion>` so it is neither counted nor
     * re-attempted until the gate itself improves.
     */
    suspend fun indexPdf(
        storageId: String,
        version: Int,
    ) {
        if (isCurrentPdfMarker(pdfBodyStore.readBodyIndex(storageId, version))) return
        locks.getOrPut(storageId) { Mutex() }.withLock {
            if (isCurrentPdfMarker(pdfBodyStore.readBodyIndex(storageId, version))) return
            // HTML-defer, re-checked here under the lock — PDF never clobbers a current-model HTML body index.
            val arxivId = PaperRef.fromStorageId(storageId).arxivIdOrNull
            if (arxivId != null && htmlStorage.hasCurrentModelBodyIndex(arxivId, modelName)) return
            val file = pdfBodyStore.localPdf(storageId) ?: return // no cached PDF (raced with a delete)
            val text = pdfExtract(file)
            if (isAcceptablePdfText(text)) {
                when (ragIndexer.indexPaperBody(storageId, text)) {
                    is AppResult.Success -> pdfBodyStore.storeBodyIndex(storageId, version, "$OK$modelName")
                    is AppResult.Failure -> Unit // leave unmarked → retried on next open / worker pass
                }
            } else {
                // Garbage: remove any stale body chunks (empty text deletes them) + stamp SKIP.
                ragIndexer.indexPaperBody(storageId, "")
                pdfBodyStore.storeBodyIndex(storageId, version, "$SKIP$gateVersion")
            }
        }
    }

    /** Bounded worker catch-all over cached PDFs missing a current marker (runs after the HTML backfill). */
    suspend fun backfillPdf(
        limit: Int,
        shouldStop: () -> Boolean = { false },
    ) {
        val candidates = pdfBodyStore.cachedPdfs().filter { !isCurrentPdfMarker(it.marker) }.take(limit)
        for (ref in candidates) {
            if (shouldStop()) return
            indexPdf(ref.storageId, ref.version)
        }
    }

    private fun isCurrentPdfMarker(marker: String?): Boolean =
        marker == "$OK$modelName" || marker == "$SKIP$gateVersion"

    private companion object {
        const val OK = "OK:"
        const val SKIP = "SKIP:"
    }
}
