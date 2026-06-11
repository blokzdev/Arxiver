package dev.blokz.arxiver.feature.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.model.ArxivCategory
import dev.blokz.arxiver.data.CategoryRepository
import dev.blokz.arxiver.data.CategoryWithFollowState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BrowseUiState(
    val groups: List<CategoryGroup> = emptyList(),
)

data class CategoryGroup(
    val name: String,
    val categories: List<CategoryWithFollowState>,
)

@HiltViewModel
class BrowseViewModel
    @Inject
    constructor(
        private val categoryRepository: CategoryRepository,
    ) : ViewModel() {
        val uiState: StateFlow<BrowseUiState> =
            categoryRepository.observeGroupedCategories()
                .map { grouped ->
                    BrowseUiState(groups = grouped.map { (name, categories) -> CategoryGroup(name, categories) })
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState())

        fun setFollowed(
            category: ArxivCategory,
            followed: Boolean,
        ) {
            viewModelScope.launch { categoryRepository.setFollowed(category, followed) }
        }
    }
