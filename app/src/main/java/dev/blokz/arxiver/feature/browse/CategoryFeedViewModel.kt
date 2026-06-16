package dev.blokz.arxiver.feature.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.data.PaperRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryFeedUiState(
    val code: String = "",
    val title: String = "",
    val papers: List<Paper> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val nextStart: Int? = 0,
    val error: AppError? = null,
)

@HiltViewModel
class CategoryFeedViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val paperRepository: PaperRepository,
    ) : ViewModel() {
        private val code: String = checkNotNull(savedStateHandle["code"])
        private val title: String = savedStateHandle["title"] ?: code

        private val _uiState = MutableStateFlow(CategoryFeedUiState(code = code, title = title))
        val uiState: StateFlow<CategoryFeedUiState> = _uiState.asStateFlow()

        init {
            // Cache-first: show locally-cached papers instantly (no network), so Browse never
            // waits behind the rate-limited sync queue. Fetch from arXiv only when the cache is
            // empty; pull-to-refresh and scroll-to-load-more still go to the network.
            viewModelScope.launch {
                val cached = paperRepository.cachedCategory(code)
                if (cached.isEmpty()) {
                    loadMore()
                } else {
                    _uiState.update { it.copy(papers = cached, nextStart = cached.size, loading = false) }
                }
            }
        }

        fun refresh() {
            _uiState.update { it.copy(papers = emptyList(), nextStart = 0, error = null) }
            loadMore()
        }

        fun loadMore() {
            val state = _uiState.value
            val start = state.nextStart ?: return
            if (state.loading || state.loadingMore) return

            _uiState.update {
                it.copy(loading = it.papers.isEmpty(), loadingMore = it.papers.isNotEmpty(), error = null)
            }
            viewModelScope.launch {
                when (val result = paperRepository.categoryLatest(code, start)) {
                    is AppResult.Success ->
                        _uiState.update {
                            it.copy(
                                papers = (it.papers + result.value.papers).distinctBy { p -> p.id },
                                nextStart = result.value.nextStart,
                                loading = false,
                                loadingMore = false,
                            )
                        }
                    is AppResult.Failure ->
                        _uiState.update {
                            it.copy(loading = false, loadingMore = false, error = result.error)
                        }
                }
            }
        }
    }
