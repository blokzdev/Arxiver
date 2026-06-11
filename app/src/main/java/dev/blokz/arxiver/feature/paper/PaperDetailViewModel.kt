package dev.blokz.arxiver.feature.paper

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.data.PaperRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PaperDetailUiState(
    val paper: Paper? = null,
    val loading: Boolean = true,
    val notFound: Boolean = false,
)

@HiltViewModel
class PaperDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val paperRepository: PaperRepository,
    ) : ViewModel() {
        private val paperId = ArxivId(checkNotNull(savedStateHandle["id"]))

        private val _uiState = MutableStateFlow(PaperDetailUiState())
        val uiState: StateFlow<PaperDetailUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                val paper = paperRepository.paper(paperId)
                _uiState.update { it.copy(paper = paper, loading = false, notFound = paper == null) }
            }
        }
    }
