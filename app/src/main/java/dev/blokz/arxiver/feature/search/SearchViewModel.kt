package dev.blokz.arxiver.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tracing.Trace
import androidx.tracing.trace
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.getOrNull
import dev.blokz.arxiver.core.database.dao.ChunkEmbeddingDao
import dev.blokz.arxiver.core.database.dao.SearchDao
import dev.blokz.arxiver.core.database.fts.LocalKeywordSearch
import dev.blokz.arxiver.core.database.toListDomain
import dev.blokz.arxiver.core.ml.EmbeddingService
import dev.blokz.arxiver.core.ml.ModelDownloader
import dev.blokz.arxiver.core.ml.ModelState
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.network.FollowCategoryOption
import dev.blokz.arxiver.core.network.arxiv.ArxivQuery
import dev.blokz.arxiver.core.network.arxiv.SearchFilter
import dev.blokz.arxiver.core.search.CorpusBodyRetriever
import dev.blokz.arxiver.core.search.HybridFusion
import dev.blokz.arxiver.core.search.Provenance
import dev.blokz.arxiver.core.search.SearchTrace
import dev.blokz.arxiver.core.search.VectorIndex
import dev.blokz.arxiver.data.CategoryRepository
import dev.blokz.arxiver.data.LibraryRepository
import dev.blokz.arxiver.data.PaperRepository
import dev.blokz.arxiver.sync.EmbeddingWorker
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class LocalHit(
    val paper: Paper,
    val score: Double,
    val provenance: Provenance,
)

/** Submitted-date windows offered in the filter sheet (mapped to arXiv submittedDate). */
enum class DatePreset { ANY, PAST_WEEK, PAST_MONTH, PAST_YEAR }

data class SearchUiState(
    val query: String = "",
    /** Local (library/cache) hybrid results — live as you type. */
    val localResults: List<LocalHit> = emptyList(),
    val semanticActive: Boolean = false,
    /**
     * Papers whose PDF/HTML **body** text matches the query but whose title/abstract does not (P-FullText
     * PFT.3) — surfaced as a distinct "Also found in full text" section, computed off the main-search path.
     */
    val bodyOnlyResults: List<LocalHit> = emptyList(),
    /** How many papers are full-text (body) indexed — a query-independent honest coverage count (PFT.3). */
    val fullTextIndexedCount: Int = 0,
    /** Online results — explicit submit only (arXiv native, or one OpenAlex call for a non-arXiv source). */
    val results: List<Paper> = emptyList(),
    val searching: Boolean = false,
    val loadingMore: Boolean = false,
    val nextStart: Int? = null,
    val totalResults: Int? = null,
    val error: AppError? = null,
    val searched: Boolean = false,
    /** The Online leg's source (P-Explorer PE.3). arXiv = the untouched native path; others ride OpenAlex. */
    val onlineSource: Source = Source.ARXIV,
    /** Optional server-side Field narrowing for a non-arXiv source (PE.1 curated vocab); null = all fields. */
    val onlineField: FollowCategoryOption? = null,
    /** Sources the user follows — ★-sorted to the top of the source picker. */
    val followedOrigins: Set<Source> = emptySet(),
    // --- structured arXiv filters (arXiv tab) ---
    val scope: SearchFilter.Field = SearchFilter.Field.ALL,
    val categories: List<String> = emptyList(),
    val datePreset: DatePreset = DatePreset.ANY,
    val sortBy: ArxivQuery.SortBy = ArxivQuery.SortBy.RELEVANCE,
    val sortOrder: ArxivQuery.SortOrder = ArxivQuery.SortOrder.DESCENDING,
) {
    /** Whether any advanced (beyond the plain box) filter is set — drives the "Filters •" affordance. */
    val filtersActive: Boolean
        get() =
            scope != SearchFilter.Field.ALL ||
                categories.isNotEmpty() ||
                datePreset != DatePreset.ANY ||
                sortBy != ArxivQuery.SortBy.RELEVANCE
}

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
        private val corpusBodyRetriever: CorpusBodyRetriever,
        private val chunkEmbeddingDao: ChunkEmbeddingDao,
        private val libraryRepository: LibraryRepository,
        categoryRepository: CategoryRepository,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                SearchUiState(
                    // The Online scope survives process death coherently with the rememberSaveable tab (PE.3):
                    // a restored Filters/source sheet must agree with the restored source. A bad value → arXiv.
                    onlineSource =
                        savedStateHandle.get<String>(KEY_ONLINE_SOURCE)
                            ?.let { saved -> runCatching { Source.valueOf(saved) }.getOrNull() }
                            ?: Source.ARXIV,
                ),
            )
        val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

        private val queryFlow = MutableStateFlow("")
        private var searchJob: Job? = null
        private var submittedFilter: SearchFilter? = null

        init {
            observeLocal()
            // Query-independent full-text coverage count (PFT.3) — updates as body indexing progresses.
            viewModelScope.launch {
                chunkEmbeddingDao.observeBodyIndexedPaperCount(EmbeddingWorker.MODEL_NAME).collect { count ->
                    _uiState.update { it.copy(fullTextIndexedCount = count) }
                }
            }
            // Followed sources ★-sort to the top of the picker. Pure DB → zero network on collection.
            viewModelScope.launch {
                categoryRepository.observeFollows().collect { follows ->
                    val origins =
                        follows.mapNotNull { f -> Source.entries.firstOrNull { it.wire == f.origin } }.toSet()
                    _uiState.update { it.copy(followedOrigins = origins) }
                }
            }
        }

        @OptIn(FlowPreview::class)
        private fun observeLocal() {
            viewModelScope.launch {
                queryFlow
                    .debounce(LOCAL_DEBOUNCE_MS)
                    .distinctUntilChanged()
                    .collect { query ->
                        if (query.isBlank()) {
                            bodyLegJob?.cancel()
                            _uiState.update { it.copy(localResults = emptyList(), bodyOnlyResults = emptyList()) }
                        } else {
                            runLocalSearch(query)
                        }
                    }
            }
        }

        // Monotonic cookie pairing begin/endAsyncSection for the hybrid_search trace. Access is sequential (a single
        // observeLocal collector drives runLocalSearch), so a plain counter can't produce overlapping cookies.
        private val hybridSearchCookie = java.util.concurrent.atomic.AtomicInteger(0)

        /** The off-critical-path full-text body leg (PFT.3); cancelled + relaunched per query. */
        private var bodyLegJob: Job? = null

        /**
         * Hybrid local search (SPEC-SEARCH §3): keyword + semantic legs fused when
         * the model is ready; silent keyword-only degradation otherwise.
         */
        private suspend fun runLocalSearch(query: String) {
            // Cancel the previous body leg BEFORE the new main results clear the section, so a stale body
            // emit can never briefly pair with the new query's main results.
            bodyLegJob?.cancel()
            val metadataIds = runMainHybridSearch(query)
            // The full-text body leg runs OFF the traced main-search path in its own cancellable job, so it
            // neither inflates the D2 latency budget nor blocks the next query's main results (PFT.3).
            bodyLegJob = viewModelScope.launch { runBodyFullTextLeg(query, exclude = metadataIds) }
        }

        /**
         * The paper-level keyword + semantic hybrid search (SPEC-SEARCH §3), traced as `hybrid_search` — the true
         * end-to-end D2 the PP.3b SearchTraceBenchmark reads (INCLUDES embedQuery's JNI cost; the sync slice excludes
         * it). Async (name+cookie, thread-independent) is the only correct primitive across the suspension points;
         * try/finally is mandatory because embedQuery can fail and viewModelScope can cancel — the section must always
         * close. Calls are sequential (single observeLocal collector) so the monotonic cookie can't collide. Returns
         * the metadata-leg paper ids so the body leg can exclude papers already matched by title/abstract.
         */
        private suspend fun runMainHybridSearch(query: String): Set<String> {
            val cookie = hybridSearchCookie.incrementAndGet()
            Trace.beginAsyncSection(SearchTrace.HYBRID_SEARCH, cookie)
            try {
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

                // Sync slice — no suspension inside (HybridFusion.fuse is a pure non-suspending object method).
                val fused =
                    trace(SearchTrace.HYBRID_FUSE) {
                        HybridFusion.fuse(keyword = keywordLeg, semantic = semanticLeg)
                    }

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
                // Clear the previous query's body section as the new main results appear; the body job refills it.
                _uiState.update {
                    it.copy(localResults = hits, semanticActive = modelReady, bodyOnlyResults = emptyList())
                }
                return (keywordLeg.map { it.first } + semanticLeg.map { it.first }).toSet()
            } finally {
                Trace.endAsyncSection(SearchTrace.HYBRID_SEARCH, cookie)
            }
        }

        /**
         * The "Also found in full text" leg (PFT.3): papers whose BODY text matches [query] but whose title/abstract
         * (the [exclude] metadata ids) does not — MAX-BM25-ranked by [CorpusBodyRetriever], resolved to rows, capped.
         * Deliberately off the traced D2 path and flood-safe (never merged into the main list).
         */
        private suspend fun runBodyFullTextLeg(
            query: String,
            exclude: Set<String>,
        ) {
            val bodyOnlyIds = corpusBodyRetriever.retrieve(query).filter { it !in exclude }
            if (bodyOnlyIds.isEmpty()) {
                _uiState.update { it.copy(bodyOnlyResults = emptyList()) }
                return
            }
            val byId = searchDao.papersByIds(bodyOnlyIds).associateBy { it.id }
            val hits =
                bodyOnlyIds.mapNotNull { id ->
                    // Provenance is unused here — the section header conveys full-text; rows carry no per-row badge.
                    byId[id]?.let { LocalHit(paper = it.toListDomain(), score = 0.0, provenance = Provenance.KEYWORD) }
                }
            _uiState.update { it.copy(bodyOnlyResults = hits) }
        }

        fun onQueryChange(query: String) {
            _uiState.update { it.copy(query = query) }
            queryFlow.value = query
        }

        fun setScope(field: SearchFilter.Field) = _uiState.update { it.copy(scope = field) }

        fun toggleCategory(code: String) =
            _uiState.update {
                it.copy(categories = if (code in it.categories) it.categories - code else it.categories + code)
            }

        fun clearCategories() = _uiState.update { it.copy(categories = emptyList()) }

        fun setDatePreset(preset: DatePreset) = _uiState.update { it.copy(datePreset = preset) }

        fun setSort(
            sortBy: ArxivQuery.SortBy,
            sortOrder: ArxivQuery.SortOrder = _uiState.value.sortOrder,
        ) = _uiState.update { it.copy(sortBy = sortBy, sortOrder = sortOrder) }

        /** The current UI state expressed as a structured arXiv [SearchFilter]. */
        private fun currentFilter(): SearchFilter {
            val state = _uiState.value
            val from =
                when (state.datePreset) {
                    DatePreset.ANY -> null
                    DatePreset.PAST_WEEK -> LocalDate.now().minusWeeks(1)
                    DatePreset.PAST_MONTH -> LocalDate.now().minusMonths(1)
                    DatePreset.PAST_YEAR -> LocalDate.now().minusYears(1)
                }
            return SearchFilter(
                term = state.query.trim(),
                field = state.scope,
                categories = state.categories,
                from = from,
                sortBy = state.sortBy,
                sortOrder = state.sortOrder,
            )
        }

        /**
         * Switch the Online scope's source (PE.3). Cancels any in-flight search (a slow response from the old
         * source must not land in the new one's state), clears results, and deliberately does NOT re-search —
         * every OpenAlex call is an explicit submit (the metering red line).
         */
        fun setOnlineSource(source: Source) {
            if (source == _uiState.value.onlineSource) return
            searchJob?.cancel()
            savedStateHandle[KEY_ONLINE_SOURCE] = source.name
            submittedFilter = null
            _uiState.update {
                it.copy(
                    onlineSource = source,
                    onlineField = null,
                    results = emptyList(),
                    searching = false,
                    loadingMore = false,
                    nextStart = null,
                    totalResults = null,
                    error = null,
                    searched = false,
                )
            }
        }

        /** Narrow (or clear, with null) the non-arXiv source's Field filter. Applies on the next explicit submit. */
        fun setOnlineField(field: FollowCategoryOption?) = _uiState.update { it.copy(onlineField = field) }

        fun submit() {
            val state = _uiState.value
            if (state.onlineSource == Source.ARXIV) {
                val filter = currentFilter()
                if (filter.isEmpty) return
                submittedFilter = filter
                searchJob?.cancel()
                _uiState.update {
                    it.copy(
                        results = emptyList(),
                        searching = true,
                        error = null,
                        searched = true,
                        totalResults = null,
                        nextStart = null,
                        loadingMore = false,
                    )
                }
                searchJob = viewModelScope.launch { runSearch(start = 0) }
            } else {
                val query = state.query.trim()
                if (query.isEmpty()) return
                // Null the arXiv pagination snapshot: loadMore() must have NOTHING to resume on this scope
                // (belt to nextStart=null's braces — a stale filter could otherwise page arXiv mid-SSRN).
                submittedFilter = null
                searchJob?.cancel()
                _uiState.update {
                    it.copy(
                        results = emptyList(),
                        searching = true,
                        error = null,
                        searched = true,
                        totalResults = null,
                        nextStart = null,
                        loadingMore = false,
                    )
                }
                searchJob =
                    viewModelScope.launch {
                        runExternalSearch(state.onlineSource, query, state.onlineField?.value)
                    }
            }
        }

        /** One OpenAlex call per explicit submit — un-paginated v1, so a scroll can never bill a BYOK key. */
        private suspend fun runExternalSearch(
            source: Source,
            query: String,
            fieldToken: String?,
        ) {
            when (val result = paperRepository.searchExternal(source, query, fieldToken)) {
                is AppResult.Success ->
                    _uiState.update {
                        it.copy(
                            results = result.value.papers,
                            totalResults = result.value.totalResults,
                            nextStart = null,
                            searching = false,
                            loadingMore = false,
                        )
                    }
                is AppResult.Failure ->
                    _uiState.update { it.copy(searching = false, loadingMore = false, error = result.error) }
            }
        }

        fun loadMore() {
            val state = _uiState.value
            val start = state.nextStart ?: return
            if (state.searching || state.loadingMore) return
            _uiState.update { it.copy(loadingMore = true) }
            searchJob = viewModelScope.launch { runSearch(start) }
        }

        private suspend fun runSearch(start: Int) {
            val filter = submittedFilter ?: return
            when (val result = paperRepository.searchArxiv(filter, start)) {
                is AppResult.Success ->
                    _uiState.update {
                        it.copy(
                            // Key on the opaque storage id, NEVER `p.id` — that shim `error()`s on a non-arXiv
                            // paper, so the old `distinctBy { p.id }` crashed the instant a multi-source result
                            // reached this list (P-Explorer PE.0; prerequisite for PE.3 search).
                            results = (it.results + result.value.papers).distinctBy { p -> p.ref.storageId },
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

        fun save(paperId: String) = viewModelScope.launch { libraryRepository.save(paperId) }

        fun saveAll(ids: Collection<String>) = viewModelScope.launch { ids.forEach { libraryRepository.save(it) } }

        /**
         * Open a search hit: persist-on-interaction, then navigate with the RETURNED storage id — the atomic
         * reuse-or-insert can re-key the hit onto an existing arXiv/other-origin row (PE.3). An arXiv result is
         * already cached by the search itself → harmless cache hit.
         */
        fun openHit(
            paper: Paper,
            onReady: (String) -> Unit,
        ) = viewModelScope.launch { onReady(paperRepository.cacheSearchHit(paper).ref.storageId) }

        /** Save a search hit: persist FIRST (the library row is FK'd to `papers`), then save under the winning id. */
        fun saveHit(paper: Paper) =
            viewModelScope.launch {
                val stored = paperRepository.cacheSearchHit(paper)
                libraryRepository.save(stored.ref.storageId)
            }

        /** Persist a multi-selection before handing its ids to save/Organize/Dispatch (same FK + re-key contract). */
        fun persistHits(
            papers: List<Paper>,
            onReady: (List<String>) -> Unit,
        ) = viewModelScope.launch { onReady(papers.map { paperRepository.cacheSearchHit(it).ref.storageId }) }

        companion object {
            private const val LOCAL_DEBOUNCE_MS = 350L
            private const val SEMANTIC_LEG_K = 30
            private const val KEY_ONLINE_SOURCE = "online_source"
        }
    }
