package dev.blokz.arxiver.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.database.entity.FollowEntity
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.data.CategoryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A removable follow row on the manage screen (PFP.2). Carries the [entity] so unfollow names its
 * `(type, value, origin)`; the render-only helpers derive from it (no logic in the Composable).
 */
data class FollowRowUi(val entity: FollowEntity) {
    /** Whole-source follows store `value=""` (PreprintSourceRegistry.WHOLE_SOURCE_VALUE) — rendered "All of X". */
    val isWholeSource: Boolean get() = entity.value.isBlank()
    val label: String get() = entity.label
    val type: String get() = entity.type
}

/** One source's follows, grouped for the manage screen. */
data class FollowGroupUi(
    val source: Source,
    val rows: List<FollowRowUi>,
)

data class ManageFollowsUiState(
    val groups: List<FollowGroupUi> = emptyList(),
    val loading: Boolean = true,
) {
    /** A settled empty DB — distinct from `loading` (the flow hasn't emitted its first list yet). */
    val isEmpty: Boolean get() = !loading && groups.isEmpty()
}

/**
 * Backing state for the "Following" management sheet (P-FeedPolish PFP.2). Maps the UNFILTERED follow rows
 * (`observeFollows()` = all origins/types, pure DB → the zero-network-on-open red line holds structurally) into
 * per-source groups, so the arXiv group is the only place an author/query follow can be removed. `wire`→[Source]
 * uses the public [Source.entries] scan, NOT the `internal` `Source.BY_PREFIX` (which excludes ARXIV), so an
 * `origin='arxiv'` follow groups under arXiv rather than silently falling back.
 */
@HiltViewModel
class ManageFollowsViewModel
    @Inject
    constructor(
        private val categoryRepository: CategoryRepository,
    ) : ViewModel() {
        val uiState: StateFlow<ManageFollowsUiState> =
            categoryRepository.observeFollows()
                .map { follows -> ManageFollowsUiState(groups = groupBySource(follows), loading = false) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManageFollowsUiState())

        fun unfollow(follow: FollowEntity) {
            viewModelScope.launch { categoryRepository.removeFollow(follow) }
        }

        companion object {
            /** `wire` → [Source], defaulting to arXiv (its own wire is `arxiv`; `BY_PREFIX` would miss it). */
            fun wireToSource(wire: String): Source = Source.entries.firstOrNull { it.wire == wire } ?: Source.ARXIV

            /**
             * Group follows by source (declaration order → arXiv first), whole-source rows first within a group
             * then alphabetical by label. Pure so it is directly unit-testable without Hilt/coroutines.
             */
            fun groupBySource(follows: List<FollowEntity>): List<FollowGroupUi> =
                follows
                    .groupBy { wireToSource(it.origin) }
                    .toList()
                    .sortedBy { (source, _) -> source.ordinal }
                    .map { (source, rows) ->
                        FollowGroupUi(
                            source = source,
                            rows =
                                rows
                                    .map { FollowRowUi(it) }
                                    .sortedWith(
                                        compareByDescending<FollowRowUi> { it.isWholeSource }
                                            .thenBy { it.type }
                                            .thenBy { it.label.lowercase() },
                                    ),
                        )
                    }
        }
    }
