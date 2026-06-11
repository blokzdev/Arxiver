package dev.blokz.arxiver.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.getOrNull
import dev.blokz.arxiver.core.database.dao.SearchDao
import dev.blokz.arxiver.core.database.fts.LocalKeywordSearch
import dev.blokz.arxiver.core.database.toListDomain
import dev.blokz.arxiver.core.ml.EmbeddingService
import dev.blokz.arxiver.core.ml.ModelDownloader
import dev.blokz.arxiver.core.ml.ModelState
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.search.HybridFusion
import dev.blokz.arxiver.core.search.Provenance
import dev.blokz.arxiver.core.search.VectorIndex
import dev.blokz.arxiver.data.PaperRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LocalHit(
    val paper: Paper,
    val score: Double,
    val provenance: Provenance,
)

data class SearchUiState(
    val query: String = "",
    /** Local (library/cache) hybrid results — live as you type. */
    val localResults: List<LocalHit> = emptyList(),
    val semanticActive: Boolean = false,
    /** Online arXiv results — explicit submit. */
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
        private val localKeywordSearch: LocalKeywordSearch,
        private val vectorIndex: VectorIndex,
        private val embeddingService: EmbeddingService,
        private val modelDownloader: ModelDownloader,
        private val searchDao: SearchDao,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SearchUiState())
        val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

        private val queryFlow = MutableStateFlow("")
        private var searchJob: Job? = null

        init {
            observeLocal()
        }

        @OptIn(FlowPreview::class)
        private fun observeLocal() {
            viewModelScope.launch {
                queryFlow
                    .debounce(LOCAL_DEBOUNCE_MS)
                    .distinctUntilChanged()
                    .collect { query ->
                        if (query.isBlank()) {
                            _uiState.update { it.copy(localResults = emptyList()) }
                        } else {
                            runLocalSearch(query)
                        }
                    }
            }
        }

        /**
         * Hybrid local search (SPEC-SEARCH §3): keyword + semantic legs fused when
         * the model is ready; silent keyword-only degradation otherwise.
         */
        private suspend fun runLocalSearch(query: String) {
            val keywordHits = localKeywordSearch.search(query)
            val keywordLeg = keywordHits.map { it.paper.id to it.score }
            val papersById = keywordHits.associate { it.paper.id to it.paper }.toMutableMap()

            val modelReady = modelDownloader.state.value is ModelState.Ready
            val semanticLeg =
                if (modelReady) {
                    embeddingService.embedQuery(query).getOrNull()?.let { queryVector ->
                        vectorIndex.topK(queryVector, k = SEMANTIC_LEG_K).map { it.paperId to it.similarity }
                    }.orEmpty()
                } else {
                    emptyList()
                }

            val fused = HybridFusion.fuse(keyword = keywordLeg, semantic = semanticLeg)

            val missing = fused.map { it.paperId }.filter { it !in papersById }
            if (missing.isNotEmpty()) {
                searchDao.papersByIds(missing).forEach { papersById[it.id] = it }
            }

            val hits =
                fused.mapNotNull { hit ->
                    papersById[hit.paperId]?.let {
                        LocalHit(paper = it.toListDomain(), score = hit.score, provenance = hit.provenance)
                    }
                }
            _uiState.update { it.copy(localResults = hits, semanticActive = modelReady) }
        }

        fun onQueryChange(query: String) {
            _uiState.update { it.copy(query = query) }
            queryFlow.value = query
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

        companion object {
            private const val LOCAL_DEBOUNCE_MS = 350L
            private const val SEMANTIC_LEG_K = 30
        }
    }
