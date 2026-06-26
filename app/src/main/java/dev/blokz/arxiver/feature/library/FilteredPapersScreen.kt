package dev.blokz.arxiver.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Hub
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
import androidx.compose.ui.platform.LocalContext
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
import dev.blokz.arxiver.feature.paper.ask.ConversationMarkdown
import dev.blokz.arxiver.feature.paper.ask.ConversationMarkdownLabels
import dev.blokz.arxiver.ui.components.EmptyState
import dev.blokz.arxiver.ui.components.SelectionState
import dev.blokz.arxiver.ui.components.SelectionTopBar
import dev.blokz.arxiver.ui.components.SkeletonList
import dev.blokz.arxiver.ui.components.SwipeablePaperRow
import dev.blokz.arxiver.ui.components.rememberSelectionState
import dev.blokz.arxiver.ui.shareText
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FilteredPapersViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val libraryRepository: LibraryRepository,
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

        fun save(paperId: String) = viewModelScope.launch { libraryRepository.save(paperId) }

        fun saveAll(ids: Collection<String>) = viewModelScope.launch { ids.forEach { libraryRepository.save(it) } }

        /** Swipe-left/remove takes the paper out of *this* collection or tag, not the library. */
        fun removeFromScope(paperId: String) =
            viewModelScope.launch {
                if (mode == "collection") {
                    libraryRepository.removeFromCollection(id, paperId)
                } else {
                    libraryRepository.removeTag(paperId, id)
                }
            }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilteredPapersScreen(
    onBack: () -> Unit,
    onPaperClick: (String) -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenRoutines: () -> Unit = {},
    onOpenKnowledgeMap: (Long) -> Unit = {},
    viewModel: FilteredPapersViewModel = hiltViewModel(),
) {
    val papers by viewModel.papers.collectAsState()
    val collectionId = viewModel.collectionId
    var showAsk by remember { mutableStateOf(false) }
    var showOrganize by remember { mutableStateOf(false) }
    var showDispatch by remember { mutableStateOf(false) }
    val selection = rememberSelectionState()
    val context = LocalContext.current
    val exportLabels =
        ConversationMarkdownLabels(
            you = stringResource(R.string.ask_export_you),
            assistant = stringResource(R.string.ask_export_assistant),
            sources = stringResource(R.string.ask_export_sources),
            footer = stringResource(R.string.ask_export_footer),
        )

    BackHandler(enabled = selection.isActive) { selection.clear() }

    Scaffold(
        topBar = {
            if (selection.isActive) {
                SelectionTopBar(count = selection.count, onClear = { selection.clear() }) {
                    IconButton(onClick = { showOrganize = true }) {
                        Icon(Icons.Filled.CreateNewFolder, stringResource(R.string.cd_add_to_organize))
                    }
                    IconButton(onClick = { viewModel.saveAll(selection.selected) }) {
                        Icon(Icons.Filled.BookmarkAdd, stringResource(R.string.cd_save_selected))
                    }
                    IconButton(onClick = { showDispatch = true }) {
                        Icon(Icons.Filled.AutoAwesome, stringResource(R.string.cd_send_to_claude))
                    }
                }
            } else {
                TopAppBar(
                    title = { Text(viewModel.title) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                        }
                    },
                    actions = {
                        if (collectionId != null) {
                            IconButton(onClick = { onOpenKnowledgeMap(collectionId) }) {
                                Icon(Icons.Filled.Hub, stringResource(R.string.cd_open_knowledge_map))
                            }
                            IconButton(onClick = { showAsk = true }) {
                                Icon(Icons.AutoMirrored.Filled.Chat, stringResource(R.string.cd_chat_collection))
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            FilteredPapersContent(
                rows = papers,
                selection = selection,
                onPaperClick = onPaperClick,
                onSave = viewModel::save,
                onRemove = viewModel::removeFromScope,
            )
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
            // Cross-refs navigate from collection chat too; pinning is omitted (no single paper).
            onOpenCrossRef = { rawId ->
                dev.blokz.arxiver.core.model.ArxivId.parse(rawId)?.let { (id, _) ->
                    showAsk = false
                    onPaperClick(id.value)
                }
            },
            // Share an answer / the whole collection conversation as Markdown — P-Rich R4.
            onShareAnswer = { m ->
                context.shareText(ConversationMarkdown.answer(m, exportLabels), subject = viewModel.title)
            },
            onShareConversation = { msgs ->
                context.shareText(
                    ConversationMarkdown.conversation(msgs, viewModel.title, exportLabels),
                    subject = viewModel.title,
                )
            },
        )
    }

    if (showOrganize) {
        dev.blokz.arxiver.feature.organize.OrganizeSheet(
            paperIds = selection.selected.toList(),
            onDismiss = {
                showOrganize = false
                selection.clear()
            },
        )
    }

    if (showDispatch) {
        dev.blokz.arxiver.feature.claude.DispatchSheet(
            paperIds = selection.selected.toList(),
            onDismiss = {
                showDispatch = false
                selection.clear()
            },
            onGoToRoutines = {
                showDispatch = false
                onOpenRoutines()
            },
        )
    }
}

@Composable
private fun FilteredPapersContent(
    rows: List<LibraryPaper>?,
    selection: SelectionState,
    onPaperClick: (String) -> Unit,
    onSave: (String) -> Unit,
    onRemove: (String) -> Unit,
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
                    val id = row.paper.id.value
                    SwipeablePaperRow(
                        paper = row.paper,
                        onClick = { if (selection.isActive) selection.toggle(id) else onPaperClick(id) },
                        onLongClick = { selection.toggle(id) },
                        onSwipeSave = { onSave(id) },
                        onSwipeDismiss = { onRemove(id) },
                        status = row.status,
                        rating = row.rating,
                        selectionMode = selection.isActive,
                        selected = selection.contains(id),
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
        FilteredPapersContent(
            rows = emptyList(),
            selection = SelectionState(),
            onPaperClick = {},
            onSave = {},
            onRemove = {},
        )
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FilteredPapersListPreview() {
    dev.blokz.arxiver.ui.theme.ArxiverTheme {
        FilteredPapersContent(
            rows = dev.blokz.arxiver.ui.fixtures.PreviewFixtures.libraryPapers,
            selection = SelectionState(),
            onPaperClick = {},
            onSave = {},
            onRemove = {},
        )
    }
}
