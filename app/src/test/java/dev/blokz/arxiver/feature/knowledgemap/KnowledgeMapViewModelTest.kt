package dev.blokz.arxiver.feature.knowledgemap

import androidx.lifecycle.SavedStateHandle
import dev.blokz.arxiver.core.search.RelationEdge
import dev.blokz.arxiver.core.search.RelationEdgeKind
import dev.blokz.arxiver.core.search.RelationGraph
import dev.blokz.arxiver.core.search.RelationNode
import dev.blokz.arxiver.data.CollectionGraphResult
import dev.blokz.arxiver.data.CollectionGraphSource
import dev.blokz.arxiver.data.GraphResult
import dev.blokz.arxiver.data.RelationGraphSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeMapViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val sampleGraph =
        RelationGraph(
            nodes = listOf(RelationNode("a", "A"), RelationNode("b", "B")),
            edges = listOf(RelationEdge("a", "b", RelationEdgeKind.SIMILAR, 0.9)),
        )

    private fun vm(
        scope: String,
        id: String,
        collection: CollectionGraphSource,
        relation: RelationGraphSource,
    ) = KnowledgeMapViewModel(SavedStateHandle(mapOf("scope" to scope, "id" to id)), collection, relation)

    @Test
    fun `collection scope surfaces the source's scene`() =
        runTest(dispatcher) {
            val model =
                vm(
                    "collection",
                    "5",
                    collection = { CollectionGraphResult.Ready(buildScene()) },
                    relation = { GraphResult.NoRelations },
                )
            advanceUntilIdle()
            val state = assertIs<KnowledgeMapUiState.Ready>(model.uiState.value)
            assertEquals(2, state.scene.nodes.size)
        }

    @Test
    fun `collection with no relations maps to the Empty state`() =
        runTest(dispatcher) {
            val model =
                vm(
                    "collection",
                    "5",
                    collection = { CollectionGraphResult.NoRelations },
                    relation = { GraphResult.NoRelations },
                )
            advanceUntilIdle()
            assertIs<KnowledgeMapUiState.Empty>(model.uiState.value)
        }

    @Test
    fun `paper scope builds a scene from the relation graph source`() =
        runTest(dispatcher) {
            val model =
                vm(
                    "paper",
                    "2401.00001",
                    collection = { CollectionGraphResult.NoPapers },
                    relation = { GraphResult.Ready(sampleGraph) },
                )
            advanceUntilIdle()
            val state = assertIs<KnowledgeMapUiState.Ready>(model.uiState.value)
            assertEquals(2, state.scene.nodes.size)
        }

    private fun buildScene() = dev.blokz.arxiver.core.search.GraphSceneBuilder.build(sampleGraph)
}
