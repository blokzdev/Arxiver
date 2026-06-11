package dev.blokz.arxiver.feature.pdf

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.network.pdf.PdfDownloader
import dev.blokz.arxiver.data.PaperRepository
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
)

@HiltViewModel
class PdfViewerViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        @ApplicationContext private val context: Context,
        private val pdfDownloader: PdfDownloader,
        private val paperRepository: PaperRepository,
    ) : ViewModel() {
        private val paperId = ArxivId(checkNotNull(savedStateHandle["id"]))

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
                val paper = paperRepository.paper(paperId)
                if (paper == null) {
                    _uiState.update { it.copy(downloading = false, error = AppError.Storage("unknown paper")) }
                    return@launch
                }
                val safeName = paperId.value.replace('/', '_') + "v${paper.latestVersion}.pdf"
                val destination = File(File(context.filesDir, "pdfs"), safeName)
                when (val result = pdfDownloader.download(paper.pdfUrl, destination)) {
                    is AppResult.Success ->
                        _uiState.update { it.copy(file = result.value, downloading = false) }
                    is AppResult.Failure ->
                        _uiState.update { it.copy(downloading = false, error = result.error) }
                }
            }
        }
    }
