package dev.blokz.arxiver.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.data.PaperRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<Paper> = emptyList(),
    val searching: Boolean = false,
    val loadingMore: Boolean = false,
    val nextStart: Int? = null,
    val totalResults: Int? = null,
    val error: AppError? = null,
    val searched: Boolean = false,
)

@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val paperRepository: PaperRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SearchUiState())
        val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

        private var searchJob: Job? = null

        fun onQueryChange(query: String) {
            _uiState.update { it.copy(query = query) }
        }

        fun submit() {
            val query = _uiState.value.query.trim()
            if (query.isEmpty()) return
            searchJob?.cancel()
            _uiState.update {
                it.copy(results = emptyList(), searching = true, error = null, searched = true, totalResults = null)
            }
            searchJob = viewModelScope.launch { runSearch(query, start = 0) }
        }

        fun loadMore() {
            val state = _uiState.value
            val start = state.nextStart ?: return
            if (state.searching || state.loadingMore) return
            _uiState.update { it.copy(loadingMore = true) }
            searchJob = viewModelScope.launch { runSearch(state.query.trim(), start) }
        }

        private suspend fun runSearch(
            query: String,
            start: Int,
        ) {
            when (val result = paperRepository.searchArxiv(query, start)) {
                is AppResult.Success ->
                    _uiState.update {
                        it.copy(
                            results = (it.results + result.value.papers).distinctBy { p -> p.id },
                            totalResults = result.value.totalResults,
                            nextStart = result.value.nextStart,
                            searching = false,
                            loadingMore = false,
                        )
                    }
                is AppResult.Failure ->
                    _uiState.update {
                        it.copy(searching = false, loadingMore = false, error = result.error)
                    }
            }
        }
    }
