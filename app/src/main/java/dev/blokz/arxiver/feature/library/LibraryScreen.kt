package dev.blokz.arxiver.feature.library

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.ui.components.PaperListItem

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
    var tab by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var showNewCollection by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showDispatch by remember { mutableStateOf(false) }
    val context = LocalContext.current

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
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectedIds.isEmpty()) {
                            stringResource(R.string.nav_library)
                        } else {
                            pluralStringResource(R.plurals.library_selected_count, selectedIds.size, selectedIds.size)
                        },
                    )
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
                        onDeleteCollection = viewModel::deleteCollection,
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            EmptyHint(stringResource(R.string.library_empty))
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
    onDeleteCollection: (Long) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNewCollection)
                        .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(
                    stringResource(R.string.library_new_collection),
                    modifier = Modifier.padding(start = 12.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        items(state.collections, key = { it.id }) { collection ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onCollectionClick(collection.id, collection.name) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    collection.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onDeleteCollection(collection.id) }) {
                    Icon(
                        Icons.Filled.Delete,
                        stringResource(R.string.cd_delete_collection, collection.name),
                    )
                }
            }
        }
        if (state.collections.isEmpty()) {
            item { EmptyHint(stringResource(R.string.library_no_collections)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagsTab(
    state: LibraryUiState,
    onTagClick: (Long, String) -> Unit,
) {
    if (state.tags.isEmpty()) {
        EmptyHint(stringResource(R.string.library_no_tags))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.tags, key = { it.id }) { tag ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onTagClick(tag.id, tag.name) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text("#${tag.name}", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
