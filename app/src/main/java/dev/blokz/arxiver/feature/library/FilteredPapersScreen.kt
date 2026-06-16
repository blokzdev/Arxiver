package dev.blokz.arxiver.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.search.RetrievalScope
import dev.blokz.arxiver.data.LibraryPaper
import dev.blokz.arxiver.data.LibraryRepository
import dev.blokz.arxiver.feature.paper.ask.AskSheet
import dev.blokz.arxiver.ui.components.EmptyState
import dev.blokz.arxiver.ui.components.PaperListItem
import dev.blokz.arxiver.ui.components.SkeletonList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class FilteredPapersViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        libraryRepository: LibraryRepository,
    ) : ViewModel() {
        val title: String = savedStateHandle["title"] ?: ""
        private val mode: String = checkNotNull(savedStateHandle["mode"])
        private val id: Long = checkNotNull(savedStateHandle.get<String>("id")).toLong()

        /** Non-null only for a collection — drives the "Chat with this collection" action. */
        val collectionId: Long? = if (mode == "collection") id else null

        // null = still loading (flow hasn't emitted); empty list = genuinely empty.
        val papers: StateFlow<List<LibraryPaper>?> =
            when (mode) {
                "collection" -> libraryRepository.observeCollectionPapers(id)
                else -> libraryRepository.observeTagPapers(id)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilteredPapersScreen(
    onBack: () -> Unit,
    onPaperClick: (String) -> Unit,
    onOpenAiSettings: () -> Unit,
    viewModel: FilteredPapersViewModel = hiltViewModel(),
) {
    val papers by viewModel.papers.collectAsState()
    val collectionId = viewModel.collectionId
    var showAsk by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (collectionId != null) {
                        IconButton(onClick = { showAsk = true }) {
                            Icon(Icons.AutoMirrored.Filled.Chat, stringResource(R.string.cd_chat_collection))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            FilteredPapersContent(rows = papers, onPaperClick = onPaperClick)
        }
    }

    if (showAsk && collectionId != null) {
        AskSheet(
            scope = RetrievalScope.Collection(collectionId),
            onDismiss = { showAsk = false },
            onConfigureProvider = {
                showAsk = false
                onOpenAiSettings()
            },
        )
    }
}

@Composable
private fun FilteredPapersContent(
    rows: List<LibraryPaper>?,
    onPaperClick: (String) -> Unit,
) {
    when {
        rows == null -> SkeletonList()
        rows.isEmpty() ->
            EmptyState(
                title = stringResource(R.string.library_filtered_empty),
                body = stringResource(R.string.library_filtered_empty_body),
                icon = Icons.Outlined.FolderOpen,
            )
        else ->
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(rows, key = { _, row -> row.paper.id.value }) { index, row ->
                    PaperListItem(
                        paper = row.paper,
                        onClick = { onPaperClick(row.paper.id.value) },
                        showDivider = index != rows.lastIndex,
                    )
                }
            }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FilteredPapersEmptyPreview() {
    dev.blokz.arxiver.ui.theme.ArxiverTheme {
        FilteredPapersContent(rows = emptyList(), onPaperClick = {})
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FilteredPapersListPreview() {
    dev.blokz.arxiver.ui.theme.ArxiverTheme {
        FilteredPapersContent(
            rows = dev.blokz.arxiver.ui.fixtures.PreviewFixtures.libraryPapers,
            onPaperClick = {},
        )
    }
}
