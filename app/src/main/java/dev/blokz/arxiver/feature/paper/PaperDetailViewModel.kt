package dev.blokz.arxiver.feature.paper

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.database.entity.FollowEntity
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.entity.NoteEntity
import dev.blokz.arxiver.core.database.entity.TagEntity
import dev.blokz.arxiver.core.database.toListDomain
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.PaperRef
import dev.blokz.arxiver.data.CategoryRepository
import dev.blokz.arxiver.data.LibraryRepository
import dev.blokz.arxiver.data.PaperRepository
import dev.blokz.arxiver.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PaperDetailUiState(
    val paper: Paper? = null,
    val loading: Boolean = true,
    val notFound: Boolean = false,
)

data class RelatedPaper(val paper: Paper, val similarity: Double)

@HiltViewModel
class PaperDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val paperRepository: PaperRepository,
        private val libraryRepository: LibraryRepository,
        private val categoryRepository: CategoryRepository,
        private val syncScheduler: SyncScheduler,
        embeddingDao: dev.blokz.arxiver.core.database.dao.EmbeddingDao,
    ) : ViewModel() {
        // The route arg is the opaque storageId (nav Uri.encode-d) — fromStorageId dispatches arXiv vs. non-arXiv.
        private val paperRef = PaperRef.fromStorageId(checkNotNull(savedStateHandle["id"]))

        private val _uiState = MutableStateFlow(PaperDetailUiState())
        val uiState: StateFlow<PaperDetailUiState> = _uiState.asStateFlow()

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

        val related: StateFlow<List<RelatedPaper>> =
            embeddingDao.observeRelated(paperRef.storageId)
                .map { rows ->
                    rows.map { RelatedPaper(paper = it.paper.toListDomain(), similarity = it.similarity) }
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
