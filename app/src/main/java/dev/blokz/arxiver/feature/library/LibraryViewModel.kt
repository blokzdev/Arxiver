package dev.blokz.arxiver.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.database.entity.CollectionEntity
import dev.blokz.arxiver.core.database.entity.TagEntity
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.data.LibraryExporter
import dev.blokz.arxiver.data.LibraryPaper
import dev.blokz.arxiver.data.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val papers: List<LibraryPaper> = emptyList(),
    val collections: List<CollectionEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val statusFilter: String? = null,
    /** Which source's papers to show (P-Explorer PE.5); null = all. */
    val sourceFilter: Source? = null,
    /**
     * Sources actually present in the (status-filtered) library, in [Source] declaration order. The chip row
     * renders ONLY these, and only when more than one exists — an all-arXiv library sees zero added chrome.
     */
    val presentSources: List<Source> = emptyList(),
)

@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val libraryRepository: LibraryRepository,
        private val exporter: LibraryExporter,
    ) : ViewModel() {
        private val statusFilter = MutableStateFlow<String?>(null)
        private val sourceFilter = MutableStateFlow<Source?>(null)

        val uiState: StateFlow<LibraryUiState> =
            combine(
                libraryRepository.observeLibrary(),
                libraryRepository.observeCollections(),
                libraryRepository.observeTags(),
                statusFilter,
                sourceFilter,
            ) { papers, collections, tags, filter, source ->
                val byStatus = if (filter == null) papers else papers.filter { it.status == filter }
                // Present-sources derive from the status-filtered list (not the source-filtered one), so the
                // chip row never erases itself the moment a chip is picked (PE.5).
                val present =
                    Source.entries.filter { s -> byStatus.any { it.paper.ref.origin == s } }
                LibraryUiState(
                    papers = if (source == null) byStatus else byStatus.filter { it.paper.ref.origin == source },
                    collections = collections,
                    tags = tags,
                    statusFilter = filter,
                    sourceFilter = source,
                    presentSources = present,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

        private val _exportContent = MutableStateFlow<ExportContent?>(null)
        val exportContent: StateFlow<ExportContent?> = _exportContent.asStateFlow()

        fun setStatusFilter(status: String?) {
            statusFilter.value = status
        }

        /** PE.5: filter the papers tab to one source (null = all). Resets nothing else. */
        fun setSourceFilter(source: Source?) {
            sourceFilter.value = source
        }

        fun createCollection(name: String) {
            if (name.isBlank()) return
            viewModelScope.launch { libraryRepository.createCollection(name) }
        }

        private val _collectionDeleted = MutableStateFlow<CollectionDeleteEvent?>(null)

        /** Latest collection deletion awaiting its undo snackbar. */
        val collectionDeleted: StateFlow<CollectionDeleteEvent?> = _collectionDeleted.asStateFlow()

        fun deleteCollection(
            id: Long,
            name: String,
        ) {
            viewModelScope.launch {
                // Capture membership first so undo can restore it exactly.
                val memberIds = libraryRepository.observeCollectionPapers(id).first().map { it.paper.ref.storageId }
                libraryRepository.deleteCollection(id)
                _collectionDeleted.value = CollectionDeleteEvent(name = name, memberIds = memberIds)
            }
        }

        /** Recreates the collection and its memberships (new row id). */
        fun undoDeleteCollection(event: CollectionDeleteEvent) {
            viewModelScope.launch {
                val newId = libraryRepository.createCollection(event.name)
                event.memberIds.forEach { libraryRepository.addToCollection(newId, it) }
            }
        }

        fun consumeCollectionDeleted() {
            _collectionDeleted.value = null
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

/** A deleted collection, held just long enough for undo. */
data class CollectionDeleteEvent(val name: String, val memberIds: List<String>)
