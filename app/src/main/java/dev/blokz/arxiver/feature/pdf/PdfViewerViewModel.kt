package dev.blokz.arxiver.feature.pdf

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.entity.ReadingPositionEntity
import dev.blokz.arxiver.core.model.PaperRef
import dev.blokz.arxiver.core.network.pdf.PdfDownloader
import dev.blokz.arxiver.data.PaperRepository
import dev.blokz.arxiver.data.PdfStorage
import dev.blokz.arxiver.data.ReadingProgressRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import javax.inject.Inject

/** A durable resume target for the PDF viewer (P-Read): the page + intra-page offset to restore on open. */
data class PdfResumeTarget(
    val pageIndex: Int,
    val offsetPx: Int,
)

data class PdfViewerUiState(
    val file: File? = null,
    val downloading: Boolean = true,
    val error: AppError? = null,
    val nightMode: Boolean = false,
    /**
     * The paper's canonical web URL (arXiv abstract page, else `doi.org/<doi>`, else the source PDF), set
     * once the paper resolves. On a download failure the reader offers this as an "open in browser" escape
     * — the graceful degrade for a non-arXiv PDF whose host isn't egress-allowlisted (never a dead retry).
     */
    val externalUrl: String? = null,
    /** The durable position to restore on open (P-Read); null = open at top (never opened, or a version skew). */
    val initialPosition: PdfResumeTarget? = null,
)

@HiltViewModel
class PdfViewerViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        @ApplicationContext private val context: Context,
        private val pdfDownloader: PdfDownloader,
        private val paperRepository: PaperRepository,
        private val readingProgressRepository: ReadingProgressRepository,
        private val applicationScope: CoroutineScope,
        private val pdfBodyIndexTrigger: dev.blokz.arxiver.rag.PdfBodyIndexTrigger,
        dispatchers: DispatcherProvider,
    ) : ViewModel() {
        // The route arg is the opaque storageId (nav `Uri.encode`s it), so it round-trips for any source.
        private val paperRef = PaperRef.fromStorageId(checkNotNull(savedStateHandle["id"]))

        /** Exposed so the page renderer (Compose-side) honors the injected dispatcher. */
        val ioDispatcher: CoroutineDispatcher = dispatchers.io

        /** Injectable wall-clock (epoch ms) for the reading-position recency timestamp; tests override. */
        internal var clock: () -> Long = { Instant.now().toEpochMilli() }

        private val _uiState = MutableStateFlow(PdfViewerUiState())
        val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

        /** The served PDF version — the reading-position row is keyed on it (a version bump soft-misses to top). */
        private var pdfVersion: Int = 1

        /** Latest genuine-scroll sample; a debounced collector persists it (mirrors the HTML persist loop). */
        private val probe = MutableStateFlow<PdfProbe?>(null)

        /** Quiet window before a settled scroll persists (mirrors the HTML reader's debounce); tests set 0. */
        internal var positionSaveDebounceMs: Long = 1_000L

        init {
            load()
            persistLoop()
        }

        fun retry() = load()

        fun toggleNightMode() = _uiState.update { it.copy(nightMode = !it.nightMode) }

        /**
         * A genuine user-scroll sample from the viewer (P-Read) — NOT called on open or on the restore jump, so
         * merely opening a PDF never creates a shelf row and reopening never inflates the recency timestamp.
         */
        fun onPositionChanged(
            page: Int,
            offset: Int,
            fraction: Float,
        ) {
            probe.value = PdfProbe(page, offset, fraction)
        }

        private fun persistLoop() {
            viewModelScope.launch {
                // collectLatest = debounce: a newer sample cancels the pending write.
                probe.collectLatest { p ->
                    if (p != null) {
                        delay(positionSaveDebounceMs)
                        writePosition(p)
                    }
                }
            }
        }

        private suspend fun writePosition(p: PdfProbe) {
            readingProgressRepository.upsert(
                ReadingPositionEntity(
                    paperId = paperRef.storageId,
                    surface = ReadingPositionEntity.SURFACE_PDF,
                    version = pdfVersion,
                    anchorId = null,
                    offsetPx = p.offset,
                    fraction = p.fraction,
                    pageIndex = p.page,
                    // PDF `finished` is never inferred (jumping to the last page for a figure/refs is routine).
                    finished = false,
                    updatedAt = clock(),
                ),
            )
        }

        override fun onCleared() {
            // Honest final flush of the last genuine sample — a back-nav within the debounce window still persists.
            val p = probe.value ?: return
            applicationScope.launch(NonCancellable) { writePosition(p) }
        }

        private fun load() {
            _uiState.update { it.copy(downloading = true, error = null) }
            viewModelScope.launch {
                val paper = paperRepository.paper(paperRef)
                if (paper == null) {
                    _uiState.update { it.copy(downloading = false, error = AppError.Storage("unknown paper")) }
                    return@launch
                }
                pdfVersion = paper.latestVersion
                val externalUrl = paper.canonicalUrl()
                // Restore only a position stored for THIS version (a version bump soft-misses to top; row kept).
                val resume =
                    readingProgressRepository.get(paperRef.storageId, ReadingPositionEntity.SURFACE_PDF)
                        ?.takeIf { it.version == pdfVersion }
                        ?.let { PdfResumeTarget(it.pageIndex ?: 0, it.offsetPx) }
                val safeName = PdfStorage.safeName(paperRef.storageId, paper.latestVersion)
                val destination = File(PdfStorage.dir(context), safeName)
                when (val result = pdfDownloader.download(paper.pdfUrl, destination)) {
                    is AppResult.Success -> {
                        _uiState.update {
                            it.copy(
                                file = result.value,
                                downloading = false,
                                externalUrl = externalUrl,
                                initialPosition = resume,
                            )
                        }
                        // P-Reader2 PFT.5.7: nudge PDF body-indexing for full-text search — fire-and-forget on the
                        // application scope (a near-done index completes if the user navigates away), only on a
                        // successful download (never on failure/restore). A no-op when already current, and it
                        // defers to a cleaner HTML body if one is indexed (HTML-preferred, PFT.5.5).
                        applicationScope.launch(ioDispatcher) {
                            pdfBodyIndexTrigger.indexPdfOnOpen(paperRef.storageId, pdfVersion)
                        }
                    }
                    is AppResult.Failure ->
                        // initialPosition is set even on failure (harmless — the error state hides the pager);
                        // a retry that succeeds then already has the resume target.
                        _uiState.update {
                            it.copy(
                                downloading = false,
                                error = result.error,
                                externalUrl = externalUrl,
                                initialPosition = resume,
                            )
                        }
                }
            }
        }

        private data class PdfProbe(
            val page: Int,
            val offset: Int,
            val fraction: Float,
        )
    }
