package dev.blokz.arxiver.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.database.entity.CollectionEntity
import dev.blokz.arxiver.core.database.entity.TagEntity
import dev.blokz.arxiver.data.LibraryExporter
import dev.blokz.arxiver.data.LibraryPaper
import dev.blokz.arxiver.data.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val papers: List<LibraryPaper> = emptyList(),
    val collections: List<CollectionEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val statusFilter: String? = null,
)

@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val libraryRepository: LibraryRepository,
        private val exporter: LibraryExporter,
    ) : ViewModel() {
        private val statusFilter = MutableStateFlow<String?>(null)

        val uiState: StateFlow<LibraryUiState> =
            combine(
                libraryRepository.observeLibrary(),
                libraryRepository.observeCollections(),
                libraryRepository.observeTags(),
                statusFilter,
            ) { papers, collections, tags, filter ->
                LibraryUiState(
                    papers = if (filter == null) papers else papers.filter { it.status == filter },
                    collections = collections,
                    tags = tags,
                    statusFilter = filter,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

        private val _exportContent = MutableStateFlow<ExportContent?>(null)
        val exportContent: StateFlow<ExportContent?> = _exportContent.asStateFlow()

        fun setStatusFilter(status: String?) {
            statusFilter.value = status
        }

        fun createCollection(name: String) {
            if (name.isBlank()) return
            viewModelScope.launch { libraryRepository.createCollection(name) }
        }

        fun deleteCollection(id: Long) {
            viewModelScope.launch { libraryRepository.deleteCollection(id) }
        }

        fun exportJson() {
            viewModelScope.launch {
                _exportContent.value = ExportContent("arxiver-library.json", "application/json", exporter.toJson())
            }
        }

        fun exportBibtex() {
            viewModelScope.launch {
                _exportContent.value = ExportContent("arxiver-library.bib", "text/x-bibtex", exporter.toBibtex())
            }
        }

        fun consumeExport() {
            _exportContent.value = null
        }
    }

data class ExportContent(val fileName: String, val mimeType: String, val content: String)
