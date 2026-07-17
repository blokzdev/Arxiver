package dev.blokz.arxiver.feature.paper

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.database.entity.FollowEntity
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.entity.NoteEntity
import dev.blokz.arxiver.core.database.entity.TagEntity
import dev.blokz.arxiver.core.database.toListDomain
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.PaperRef
import dev.blokz.arxiver.data.CategoryRepository
import dev.blokz.arxiver.data.DiscoverResult
import dev.blokz.arxiver.data.DiscoverSimilarRepository
import dev.blokz.arxiver.data.LibraryRepository
import dev.blokz.arxiver.data.NeighborsResult
import dev.blokz.arxiver.data.OaResult
import dev.blokz.arxiver.data.PaperRepository
import dev.blokz.arxiver.data.RelatedPaper
import dev.blokz.arxiver.data.SemanticNeighborsRepository
import dev.blokz.arxiver.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Ceiling on a discovery fetch — a politeness-mutex-queued call must never leave the button spinning. */
private const val DISCOVER_TIMEOUT_MS = 9_000L

data class PaperDetailUiState(
    val paper: Paper? = null,
    val loading: Boolean = true,
    val notFound: Boolean = false,
)

/**
 * The open-access resolver's UI state (P-OA), memoized for the ViewModel's lifetime so recomposition and re-taps
 * never re-bill OpenAlex. [Ready.versionOfRecord] false ⇒ the paper's own free PDF ("Open free PDF", not
 * "published"). [Error] is a distinct, RETRYABLE terminal state — an offline tap must not read as [NotFound].
 */
sealed interface OaUiState {
    data object Idle : OaUiState

    data object Loading : OaUiState

    data class Ready(
        val url: String,
        val journalName: String?,
        val versionOfRecord: Boolean,
    ) : OaUiState

    data object NotFound : OaUiState

    data object Error : OaUiState
}

/**
 * "Discover more like this" UI state (P-Discover-MLT PDM.3), memoized for the ViewModel's lifetime —
 * recomposition and re-taps never re-egress: a [Done]-Ready button reopens the sheet from memory, and only
 * a retryable [DiscoverResult.Error] permits a re-fire (the two-tap morph pattern the Co-Founder ratified
 * for P-OA). The wrapped [DiscoverResult] keeps the repository's honest terminal taxonomy verbatim.
 */
sealed interface DiscoverUiState {
    data object Idle : DiscoverUiState

    data object Loading : DiscoverUiState

    data class Done(val result: DiscoverResult) : DiscoverUiState
}

@HiltViewModel
class PaperDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val paperRepository: PaperRepository,
        private val libraryRepository: LibraryRepository,
        private val categoryRepository: CategoryRepository,
        private val syncScheduler: SyncScheduler,
        private val neighborsRepository: SemanticNeighborsRepository,
        private val discoverRepository: DiscoverSimilarRepository,
        embeddingDao: dev.blokz.arxiver.core.database.dao.EmbeddingDao,
    ) : ViewModel() {
        // The route arg is the opaque storageId (nav Uri.encode-d) — fromStorageId dispatches arXiv vs. non-arXiv.
        private val paperRef = PaperRef.fromStorageId(checkNotNull(savedStateHandle["id"]))

        private val _uiState = MutableStateFlow(PaperDetailUiState())
        val uiState: StateFlow<PaperDetailUiState> = _uiState.asStateFlow()

        private val _oa = MutableStateFlow<OaUiState>(OaUiState.Idle)
        val oa: StateFlow<OaUiState> = _oa.asStateFlow()

        private val _discover = MutableStateFlow<DiscoverUiState>(DiscoverUiState.Idle)
        val discover: StateFlow<DiscoverUiState> = _discover.asStateFlow()

        val entry: StateFlow<LibraryEntryEntity?> =
            libraryRepository.observeEntry(paperRef.storageId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        val notes: StateFlow<List<NoteEntity>> =
            libraryRepository.observeNotesFor(paperRef.storageId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val tags: StateFlow<List<TagEntity>> =
            libraryRepository.observeTagsFor(paperRef.storageId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val collections: StateFlow<List<dev.blokz.arxiver.core.database.entity.CollectionEntity>> =
            libraryRepository.observeCollections()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val memberCollectionIds: StateFlow<Set<Long>> =
            libraryRepository.observeCollectionMembershipsFor(paperRef.storageId)
                .map { it.toSet() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

        /**
         * "More like this" (P-Discover2 PD.2). The precomputed `related_papers` fast path exists only for
         * worker-processed library papers, so a search/browse-opened paper would silently show nothing; when the
         * precomputed set is empty we fall back once to a live cosine scan (via [neighborsRepository]) that works for
         * any embedded paper, and surface a typed empty cause otherwise. One-shot on open (the precompute rarely
         * changes mid-view); the scan is off the main thread inside the repo.
         */
        val related: StateFlow<NeighborsResult> =
            flow {
                emit(NeighborsResult.Loading)
                val precomputed = embeddingDao.observeRelated(paperRef.storageId).first()
                if (precomputed.isNotEmpty()) {
                    emit(
                        NeighborsResult.Ready(precomputed.map { RelatedPaper(it.paper.toListDomain(), it.similarity) }),
                    )
                } else {
                    emit(neighborsRepository.liveNeighborsFor(paperRef.storageId))
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NeighborsResult.Loading)

        /** The set of this paper's authors the user currently follows (P-Discover2 PD.1). */
        val followedAuthors: StateFlow<Set<String>> =
            categoryRepository.observeFollows()
                .map { follows ->
                    follows.filter { it.type == FollowEntity.TYPE_AUTHOR }.map { it.value }.toSet()
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

        init {
            viewModelScope.launch {
                val paper = paperRepository.paper(paperRef)
                _uiState.update { it.copy(paper = paper, loading = false, notFound = paper == null) }
            }
        }

        /**
         * Resolve the best free open-access PDF on an explicit tap (P-OA). Guards against a double-tap (no-op
         * while [OaUiState.Loading]) so at most one OpenAlex call is in flight; [OaUiState.Error] stays retryable
         * because the guard only blocks the in-flight state. Idempotent after a terminal state — the button just
         * opens the memoized URL, issuing no further network.
         */
        fun resolveOa() {
            if (_oa.value == OaUiState.Loading) return
            val paper = _uiState.value.paper ?: return
            viewModelScope.launch {
                _oa.value = OaUiState.Loading
                _oa.value =
                    when (val result = paperRepository.resolveOaFulltext(paper)) {
                        is OaResult.Found -> OaUiState.Ready(result.pdfUrl, result.journalName, result.versionOfRecord)
                        OaResult.None -> OaUiState.NotFound
                        is OaResult.Error -> OaUiState.Error
                    }
            }
        }

        /** True when the loaded paper can seed a discovery (arXiv id or DOI) — drives the button's visibility. */
        fun isDiscoverable(paper: Paper): Boolean = discoverRepository.seedIdFor(paper) != null

        /**
         * Fetch genuinely-new similar papers on an explicit tap (P-Discover-MLT PDM.3). ONE Semantic Scholar
         * call; only the seed's identifier leaves the device. Guards: no-op while [DiscoverUiState.Loading]
         * (double-tap) and after ANY non-Error terminal (Ready reopens the memoized sheet; the honest empties
         * and SeedNotFound are final for this paper) — only a retryable [DiscoverResult.Error] re-fires. The
         * timeout converts a mutex-queued hang into a retryable Error so the button can never spin forever.
         */
        fun discoverSimilar() {
            val current = _discover.value
            if (current == DiscoverUiState.Loading) return
            if (current is DiscoverUiState.Done && current.result !is DiscoverResult.Error) return
            val paper = _uiState.value.paper ?: return
            viewModelScope.launch {
                _discover.value = DiscoverUiState.Loading
                val result =
                    withTimeoutOrNull(DISCOVER_TIMEOUT_MS) { discoverRepository.discoverSimilar(paper) }
                        ?: DiscoverResult.Error(AppError.Offline)
                _discover.value = DiscoverUiState.Done(result)
            }
        }

        fun toggleSaved() {
            viewModelScope.launch {
                if (entry.value == null) {
                    libraryRepository.save(
                        paperRef.storageId,
                    )
                } else {
                    libraryRepository.unsave(paperRef.storageId)
                }
            }
        }

        /**
         * Follow/unfollow one of this paper's authors (P-Discover2 PD.1). A just-followed author starts with an empty
         * inbox, so kick the expedited one-shot sync to fetch their recent papers (unfollow needs no sync).
         */
        fun toggleAuthorFollow(name: String) {
            viewModelScope.launch {
                val nowFollowed = name !in followedAuthors.value
                categoryRepository.setAuthorFollowed(name, nowFollowed)
                if (nowFollowed) syncScheduler.syncNow()
            }
        }

        fun setStatus(status: String) {
            viewModelScope.launch { libraryRepository.setStatus(paperRef.storageId, status) }
        }

        fun setRating(rating: Int?) {
            viewModelScope.launch { libraryRepository.setRating(paperRef.storageId, rating) }
        }

        fun addNote(content: String) {
            viewModelScope.launch { libraryRepository.addNote(paperRef.storageId, content) }
        }

        fun updateNote(
            noteId: Long,
            content: String,
        ) {
            viewModelScope.launch { libraryRepository.updateNote(noteId, content) }
        }

        fun deleteNote(noteId: Long) {
            viewModelScope.launch { libraryRepository.deleteNote(noteId) }
        }

        fun addTag(name: String) {
            viewModelScope.launch { libraryRepository.addTag(paperRef.storageId, name) }
        }

        fun addToCollection(collectionId: Long) {
            viewModelScope.launch { libraryRepository.addToCollection(collectionId, paperRef.storageId) }
        }

        fun removeFromCollection(collectionId: Long) {
            viewModelScope.launch { libraryRepository.removeFromCollection(collectionId, paperRef.storageId) }
        }

        /** Create a collection and immediately add this paper to it (picker "new collection" path). */
        fun createCollectionWithPaper(name: String) {
            if (name.isBlank()) return
            viewModelScope.launch {
                val id = libraryRepository.createCollection(name)
                libraryRepository.addToCollection(id, paperRef.storageId)
            }
        }

        fun removeTag(tagId: Long) {
            viewModelScope.launch { libraryRepository.removeTag(paperRef.storageId, tagId) }
        }
    }
