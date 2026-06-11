package dev.blokz.arxiver.feature.paper

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.entity.NoteEntity
import dev.blokz.arxiver.core.database.entity.TagEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.data.LibraryRepository
import dev.blokz.arxiver.data.PaperRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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
        private val libraryRepository: LibraryRepository,
    ) : ViewModel() {
        private val paperId = ArxivId(checkNotNull(savedStateHandle["id"]))

        private val _uiState = MutableStateFlow(PaperDetailUiState())
        val uiState: StateFlow<PaperDetailUiState> = _uiState.asStateFlow()

        val entry: StateFlow<LibraryEntryEntity?> =
            libraryRepository.observeEntry(paperId.value)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        val notes: StateFlow<List<NoteEntity>> =
            libraryRepository.observeNotesFor(paperId.value)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val tags: StateFlow<List<TagEntity>> =
            libraryRepository.observeTagsFor(paperId.value)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        init {
            viewModelScope.launch {
                val paper = paperRepository.paper(paperId)
                _uiState.update { it.copy(paper = paper, loading = false, notFound = paper == null) }
            }
        }

        fun toggleSaved() {
            viewModelScope.launch {
                if (entry.value == null) {
                    libraryRepository.save(
                        paperId.value,
                    )
                } else {
                    libraryRepository.unsave(paperId.value)
                }
            }
        }

        fun setStatus(status: String) {
            viewModelScope.launch { libraryRepository.setStatus(paperId.value, status) }
        }

        fun setRating(rating: Int?) {
            viewModelScope.launch { libraryRepository.setRating(paperId.value, rating) }
        }

        fun addNote(content: String) {
            viewModelScope.launch { libraryRepository.addNote(paperId.value, content) }
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
            viewModelScope.launch { libraryRepository.addTag(paperId.value, name) }
        }

        fun removeTag(tagId: Long) {
            viewModelScope.launch { libraryRepository.removeTag(paperId.value, tagId) }
        }
    }
