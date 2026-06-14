package dev.blokz.arxiver.feature.library

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.ui.components.EmptyState
import dev.blokz.arxiver.ui.components.PaperListItem
import dev.blokz.arxiver.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onPaperClick: (String) -> Unit,
    onCollectionClick: (Long, String) -> Unit,
    onTagClick: (Long, String) -> Unit,
    onOpenRoutines: () -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val exportContent by viewModel.exportContent.collectAsState()
    val collectionDeleted by viewModel.collectionDeleted.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var showNewCollection by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showDispatch by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // System back leaves selection mode before leaving the screen.
    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds = emptySet() }

    val deletedMessage = stringResource(R.string.library_collection_deleted, collectionDeleted?.name ?: "")
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(collectionDeleted) {
        val event = collectionDeleted ?: return@LaunchedEffect
        val result =
            snackbar.showSnackbar(
                message = deletedMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDeleteCollection(event)
        viewModel.consumeCollectionDeleted()
    }

    exportContent?.let { export ->
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = export.mimeType
                putExtra(Intent.EXTRA_SUBJECT, export.fileName)
                putExtra(Intent.EXTRA_TEXT, export.content)
            }
        context.startActivity(Intent.createChooser(send, export.fileName))
        viewModel.consumeExport()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors =
                    if (selectedIds.isEmpty()) {
                        TopAppBarDefaults.topAppBarColors()
                    } else {
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                    },
                title = {
                    AnimatedContent(targetState = selectedIds.size, label = "selection-title") { count ->
                        Text(
                            if (count == 0) {
                                stringResource(R.string.nav_library)
                            } else {
                                pluralStringResource(R.plurals.library_selected_count, count, count)
                            },
                        )
                    }
                },
                actions = {
                    if (selectedIds.isNotEmpty()) {
                        IconButton(onClick = { showDispatch = true }) {
                            Icon(Icons.Filled.AutoAwesome, stringResource(R.string.cd_send_to_claude))
                        }
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Filled.Close, stringResource(R.string.cd_clear_selection))
                        }
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.cd_library_menu))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_export_json)) },
                            onClick = {
                                menuOpen = false
                                viewModel.exportJson()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_export_bibtex)) },
                            onClick = {
                                menuOpen = false
                                viewModel.exportBibtex()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_menu_routines)) },
                            onClick = {
                                menuOpen = false
                                onOpenRoutines()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_menu_history)) },
                            onClick = {
                                menuOpen = false
                                onOpenHistory()
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.library_tab_papers)) })
                Tab(tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.library_tab_collections)) })
                Tab(tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.library_tab_tags)) })
            }
            when (tab) {
                0 ->
                    PapersTab(
                        state = state,
                        selectedIds = selectedIds,
                        onFilter = viewModel::setStatusFilter,
                        onPaperClick = onPaperClick,
                        onToggleSelect = { id ->
                            selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                        },
                    )
                1 ->
                    CollectionsTab(
                        state = state,
                        onCollectionClick = onCollectionClick,
                        onNewCollection = { showNewCollection = true },
                        onDeleteCollection = { id, name -> viewModel.deleteCollection(id, name) },
                    )
                else -> TagsTab(state, onTagClick)
            }
        }
    }

    if (showDispatch) {
        dev.blokz.arxiver.feature.claude.DispatchSheet(
            paperIds = selectedIds.toList(),
            onDismiss = {
                showDispatch = false
                selectedIds = emptySet()
            },
            onGoToRoutines = {
                showDispatch = false
                onOpenRoutines()
            },
        )
    }

    if (showNewCollection) {
        NewCollectionDialog(
            onConfirm = {
                viewModel.createCollection(it)
                showNewCollection = false
            },
            onDismiss = { showNewCollection = false },
        )
    }
}

@Composable
private fun PapersTab(
    state: LibraryUiState,
    selectedIds: Set<String>,
    onFilter: (String?) -> Unit,
    onPaperClick: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            val filters =
                listOf(
                    null to stringResource(R.string.library_filter_all),
                    LibraryEntryEntity.STATUS_TO_READ to stringResource(R.string.library_filter_to_read),
                    LibraryEntryEntity.STATUS_READING to stringResource(R.string.library_filter_reading),
                    LibraryEntryEntity.STATUS_READ to stringResource(R.string.library_filter_read),
                )
            filters.forEach { (value, label) ->
                FilterChip(
                    selected = state.statusFilter == value,
                    onClick = { onFilter(value) },
                    label = { Text(label) },
                )
            }
        }
        if (state.papers.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.library_filtered_empty),
                body = stringResource(R.string.library_empty),
                icon = Icons.AutoMirrored.Outlined.LibraryBooks,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.papers, key = { it.paper.id.value }) { row ->
                    val id = row.paper.id.value
                    PaperListItem(
                        paper = row.paper,
                        onClick = {
                            if (selectedIds.isEmpty()) onPaperClick(id) else onToggleSelect(id)
                        },
                        onLongClick = { onToggleSelect(id) },
                        status = row.status,
                        rating = row.rating,
                        selectionMode = selectedIds.isNotEmpty(),
                        selected = id in selectedIds,
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionsTab(
    state: LibraryUiState,
    onCollectionClick: (Long, String) -> Unit,
    onNewCollection: () -> Unit,
    onDeleteCollection: (Long, String) -> Unit,
) {
    if (state.collections.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize()) {
            NewCollectionRow(onNewCollection)
            EmptyState(
                title = stringResource(R.string.library_no_collections),
                body = stringResource(R.string.library_empty),
                icon = Icons.Outlined.Folder,
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { NewCollectionRow(onNewCollection) }
        items(state.collections, key = { it.id }) { collection ->
            ListItem(
                headlineContent = { Text(collection.name) },
                leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = { onDeleteCollection(collection.id, collection.name) }) {
                        Icon(
                            Icons.Filled.Delete,
                            stringResource(R.string.cd_delete_collection, collection.name),
                        )
                    }
                },
                modifier =
                    Modifier
                        .clickable { onCollectionClick(collection.id, collection.name) }
                        .animateItem(),
            )
        }
    }
}

@Composable
private fun NewCollectionRow(onNewCollection: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                stringResource(R.string.library_new_collection),
                color = MaterialTheme.colorScheme.primary,
            )
        },
        leadingContent = {
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        modifier = Modifier.clickable(onClick = onNewCollection),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsTab(
    state: LibraryUiState,
    onTagClick: (Long, String) -> Unit,
) {
    if (state.tags.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.library_no_tags),
            body = stringResource(R.string.paper_tags_heading),
            icon = Icons.Outlined.Tag,
        )
        return
    }
    FlowRow(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        state.tags.forEach { tag ->
            AssistChip(
                onClick = { onTagClick(tag.id, tag.name) },
                label = { Text("#${tag.name}") },
            )
        }
    }
}

@Composable
private fun NewCollectionDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_new_collection)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.library_collection_name_hint)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NewCollectionDialogPreview() {
    dev.blokz.arxiver.ui.theme.ArxiverTheme {
        NewCollectionDialog(onConfirm = {}, onDismiss = {})
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LibraryPapersPreview() {
    dev.blokz.arxiver.ui.theme.ArxiverTheme {
        PapersTab(
            state = LibraryUiState(papers = dev.blokz.arxiver.ui.fixtures.PreviewFixtures.libraryPapers),
            selectedIds = emptySet(),
            onFilter = {},
            onPaperClick = {},
            onToggleSelect = {},
        )
    }
}
