package dev.blokz.arxiver.feature.knowledgemap

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.search.GraphScene
import dev.blokz.arxiver.core.search.GraphSceneBuilder
import dev.blokz.arxiver.data.CollectionGraphResult
import dev.blokz.arxiver.data.CollectionGraphSource
import dev.blokz.arxiver.data.GraphResult
import dev.blokz.arxiver.data.RelationGraphSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Loading / laid-out scene / typed empty for the knowledge map (P-Atlas PA.5d). */
sealed interface KnowledgeMapUiState {
    data object Loading : KnowledgeMapUiState

    data class Ready(val scene: GraphScene) : KnowledgeMapUiState

    data class Empty(
        @StringRes val message: Int,
    ) : KnowledgeMapUiState
}

/**
 * Backs the full-screen knowledge map for either a collection (`scope=collection`, the PA.5 apex) or a
 * single paper (`scope=paper`, the Connections "view as map"). Both resolve to a pure [GraphScene] off
 * the main thread: collections via [CollectionGraphSource], a paper via [RelationGraphSource] →
 * [GraphSceneBuilder.build] (reusing the very same layout/cluster/LoD pipeline). The ViewModel survives
 * configuration change, so the (potentially expensive) scene is computed once per open and held in
 * [uiState] — rotation never re-derives and nothing is parcelled.
 */
@HiltViewModel
class KnowledgeMapViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val collectionGraphSource: CollectionGraphSource,
        private val relationGraphSource: RelationGraphSource,
    ) : ViewModel() {
        private val scope: String = checkNotNull(savedStateHandle["scope"])
        private val id: String = checkNotNull(savedStateHandle["id"])

        private val _uiState = MutableStateFlow<KnowledgeMapUiState>(KnowledgeMapUiState.Loading)
        val uiState: StateFlow<KnowledgeMapUiState> = _uiState.asStateFlow()

        init {
            load()
        }

        fun load() {
            _uiState.value = KnowledgeMapUiState.Loading
            viewModelScope.launch {
                _uiState.value =
                    if (scope == SCOPE_COLLECTION) collectionScene() else paperScene()
            }
        }

        private suspend fun collectionScene(): KnowledgeMapUiState =
            when (val r = collectionGraphSource.sceneForCollection(id.toLong())) {
                is CollectionGraphResult.Ready -> KnowledgeMapUiState.Ready(r.scene)
                CollectionGraphResult.NoPapers -> KnowledgeMapUiState.Empty(R.string.knowledge_map_empty_no_papers)
                CollectionGraphResult.NoRelations ->
                    KnowledgeMapUiState.Empty(
                        R.string.knowledge_map_empty_no_relations,
                    )
            }

        private suspend fun paperScene(): KnowledgeMapUiState =
            when (val r = relationGraphSource.graphForPaper(id)) {
                is GraphResult.Ready -> KnowledgeMapUiState.Ready(GraphSceneBuilder.build(r.graph))
                GraphResult.NotEmbedded -> KnowledgeMapUiState.Empty(R.string.knowledge_map_empty_not_embedded)
                GraphResult.NoRelations -> KnowledgeMapUiState.Empty(R.string.knowledge_map_empty_no_relations)
            }

        companion object {
            const val SCOPE_COLLECTION = "collection"
            const val SCOPE_PAPER = "paper"
        }
    }
