package dev.blokz.arxiver.feature.paper

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.database.dao.CitationDao
import dev.blokz.arxiver.core.database.dao.ConnectionRow
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.ui.components.EmptyState
import dev.blokz.arxiver.ui.components.SectionHeader
import dev.blokz.arxiver.ui.components.StatusChip
import dev.blokz.arxiver.ui.components.StatusTone
import dev.blokz.arxiver.ui.theme.Spacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ConnectionsUiState(
    val references: List<ConnectionRow> = emptyList(),
    val citations: List<ConnectionRow> = emptyList(),
)

@HiltViewModel
class ConnectionsViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        citationDao: CitationDao,
    ) : ViewModel() {
        val paperId: String = checkNotNull(savedStateHandle["id"])

        val uiState: StateFlow<ConnectionsUiState> =
            combine(
                citationDao.observeReferences(paperId),
                citationDao.observeCitations(paperId),
            ) { references, citations ->
                ConnectionsUiState(references = references, citations = citations)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionsUiState())
    }

/**
 * SPEC-UI §3 Connections: list-based graph view — references and citations
 * with in-library badges. Visual canvas is a v2 candidate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    onBack: () -> Unit,
    onPaperClick: (String) -> Unit,
    onOpenMap: (String) -> Unit = {},
    viewModel: ConnectionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connections_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenMap(viewModel.paperId) }) {
                        Icon(Icons.Filled.Hub, stringResource(R.string.cd_open_knowledge_map))
                    }
                },
            )
        },
    ) { padding ->
        if (state.references.isEmpty() && state.citations.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.connections_title),
                body = stringResource(R.string.connections_empty),
                icon = Icons.Filled.Hub,
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            if (state.references.isNotEmpty()) {
                item(key = "refs-header") {
                    SectionHeader(stringResource(R.string.connections_references, state.references.size))
                }
                items(state.references, key = { "ref-" + it.paper.id }) { row ->
                    ConnectionItem(row, onClick = { onPaperClick(row.paper.id) })
                }
            }
            if (state.citations.isNotEmpty()) {
                item(key = "cites-header") {
                    SectionHeader(stringResource(R.string.connections_cited_by, state.citations.size))
                }
                items(state.citations, key = { "cite-" + it.paper.id }) { row ->
                    ConnectionItem(row, onClick = { onPaperClick(row.paper.id) })
                }
            }
        }
    }
}

@Composable
private fun ConnectionItem(
    row: ConnectionRow,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.paper.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
            )
            row.paper.citationCount?.let {
                Text(
                    text = pluralStringResource(R.plurals.connections_citation_count, it, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (row.in_library) {
            StatusChip(
                text = stringResource(R.string.connections_in_library),
                tone = StatusTone.Positive,
                icon = Icons.Filled.Bookmark,
                modifier = Modifier.padding(start = Spacing.sm),
            )
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ConnectionItemPreview() {
    dev.blokz.arxiver.ui.theme.ArxiverTheme {
        Column {
            SectionHeader("References (2)")
            ConnectionItem(
                row =
                    ConnectionRow(
                        paper = dev.blokz.arxiver.ui.fixtures.PreviewFixtures.paper.toEntity(),
                        in_library = true,
                    ),
                onClick = {},
            )
        }
    }
}
