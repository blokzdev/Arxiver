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
import dev.blokz.arxiver.core.model.PaperRef
import dev.blokz.arxiver.core.network.pdf.PdfDownloader
import dev.blokz.arxiver.data.PaperRepository
import dev.blokz.arxiver.data.PdfStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

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
)

@HiltViewModel
class PdfViewerViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        @ApplicationContext private val context: Context,
        private val pdfDownloader: PdfDownloader,
        private val paperRepository: PaperRepository,
        dispatchers: DispatcherProvider,
    ) : ViewModel() {
        // The route arg is the opaque storageId (nav `Uri.encode`s it), so it round-trips for any source.
        private val paperRef = PaperRef.fromStorageId(checkNotNull(savedStateHandle["id"]))

        /** Exposed so the page renderer (Compose-side) honors the injected dispatcher. */
        val ioDispatcher: CoroutineDispatcher = dispatchers.io

        private val _uiState = MutableStateFlow(PdfViewerUiState())
        val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

        init {
            load()
        }

        fun retry() = load()

        fun toggleNightMode() = _uiState.update { it.copy(nightMode = !it.nightMode) }

        private fun load() {
            _uiState.update { it.copy(downloading = true, error = null) }
            viewModelScope.launch {
                val paper = paperRepository.paper(paperRef)
                if (paper == null) {
                    _uiState.update { it.copy(downloading = false, error = AppError.Storage("unknown paper")) }
                    return@launch
                }
                val externalUrl = paper.canonicalUrl()
                val safeName = PdfStorage.safeName(paperRef.storageId, paper.latestVersion)
                val destination = File(PdfStorage.dir(context), safeName)
                when (val result = pdfDownloader.download(paper.pdfUrl, destination)) {
                    is AppResult.Success ->
                        _uiState.update {
                            it.copy(file = result.value, downloading = false, externalUrl = externalUrl)
                        }
                    is AppResult.Failure ->
                        _uiState.update {
                            it.copy(downloading = false, error = result.error, externalUrl = externalUrl)
                        }
                }
            }
        }
    }
