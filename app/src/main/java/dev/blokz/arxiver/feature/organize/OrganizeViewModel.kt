package dev.blokz.arxiver.feature.organize

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.data.LibraryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Membership of the selected paper(s) in a collection/tag. SOME only occurs for multi-select. */
enum class MembershipState { ALL, SOME, NONE }

/** A collection or tag row in the Organize sheet, with the selection's aggregate membership. */
data class OrganizeTarget(
    val id: Long,
    val name: String,
    val state: MembershipState,
)

data class OrganizeUiState(
    val paperCount: Int = 0,
    val collections: List<OrganizeTarget> = emptyList(),
    val tags: List<OrganizeTarget> = emptyList(),
)

/**
 * Backs the bulk "Add to collection/tag" sheet (UX2.4/2.5). Given a set of papers, it shows each
 * collection and tag with a tri-state membership (all / some / none of the selection). Tapping an
 * unchecked/partial target **adds the missing papers** (add-only — `addToCollection`/`addPaperTag`
 * are IGNORE-idempotent, so re-adding is free); tapping a fully-checked target removes all. New
 * collections/tags add every selected paper.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OrganizeViewModel
    @Inject
    constructor(
        private val libraryRepository: LibraryRepository,
    ) : ViewModel() {
        private val paperIds = MutableStateFlow<List<String>>(emptyList())

        val uiState: StateFlow<OrganizeUiState> =
            paperIds
                .flatMapLatest { ids ->
                    if (ids.isEmpty()) {
                        flowOf(OrganizeUiState())
                    } else {
                        combine(
                            libraryRepository.observeCollections(),
                            libraryRepository.observeTags(),
                            membershipCounts(ids) { libraryRepository.observeCollectionMembershipsFor(it) },
                            membershipCounts(ids) { id ->
                                libraryRepository.observeTagsFor(id).map { tags -> tags.map { it.id } }
                            },
                        ) { collections, tags, collectionCounts, tagCounts ->
                            val n = ids.size
                            OrganizeUiState(
                                paperCount = n,
                                collections =
                                    collections.map {
                                        OrganizeTarget(it.id, it.name, membership(collectionCounts[it.id] ?: 0, n))
                                    },
                                tags =
                                    tags.map {
                                        OrganizeTarget(it.id, it.name, membership(tagCounts[it.id] ?: 0, n))
                                    },
                            )
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OrganizeUiState())

        fun start(ids: List<String>) {
            paperIds.value = ids
        }

        /** Add-only by default; idempotent for papers already in the target. */
        fun addCollection(collectionId: Long) =
            viewModelScope.launch {
                paperIds.value.forEach { libraryRepository.addToCollection(collectionId, it) }
            }

        fun removeCollection(collectionId: Long) =
            viewModelScope.launch {
                paperIds.value.forEach { libraryRepository.removeFromCollection(collectionId, it) }
            }

        fun createCollectionWithSelection(name: String) =
            viewModelScope.launch {
                if (name.isBlank()) return@launch
                val id = libraryRepository.createCollection(name)
                paperIds.value.forEach { libraryRepository.addToCollection(id, it) }
            }

        fun addTag(name: String) =
            viewModelScope.launch {
                paperIds.value.forEach { libraryRepository.addTag(it, name) }
            }

        fun removeTag(tagId: Long) =
            viewModelScope.launch {
                paperIds.value.forEach { libraryRepository.removeTag(it, tagId) }
            }

        /** Adding an existing tag by name reuses the repo's insert-or-find + idempotent link. */
        fun addExistingTag(target: OrganizeTarget) {
            if (target.state == MembershipState.ALL) removeTag(target.id) else addTag(target.name)
        }

        private fun membership(
            count: Int,
            total: Int,
        ): MembershipState =
            when {
                count <= 0 -> MembershipState.NONE
                count >= total -> MembershipState.ALL
                else -> MembershipState.SOME
            }

        /** For each [ids] paper, the membership-id list; folded into per-id occurrence counts. */
        private fun membershipCounts(
            ids: List<String>,
            source: (String) -> Flow<List<Long>>,
        ): Flow<Map<Long, Int>> =
            combine(ids.map(source)) { perPaper ->
                perPaper.flatMap { it }.groupingBy { it }.eachCount()
            }
    }
