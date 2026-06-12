package dev.blokz.arxiver.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.search.Provenance
import dev.blokz.arxiver.feature.browse.ErrorState
import dev.blokz.arxiver.ui.components.PaperBadge
import dev.blokz.arxiver.ui.components.PaperListItem
import dev.blokz.arxiver.ui.components.StatusTone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onPaperClick: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_search)) }) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Filled.Close, stringResource(R.string.cd_clear_query))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.submit() }),
            )

            var tab by rememberSaveable { mutableIntStateOf(0) }
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.search_tab_library)) })
                Tab(tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.search_tab_arxiv)) })
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (tab == 0) {
                    LocalResultList(state, onPaperClick)
                } else {
                    when {
                        state.searching -> SearchingState()
                        state.error != null -> ErrorState(error = state.error, onRetry = viewModel::submit)
                        state.searched && state.results.isEmpty() -> EmptyResults()
                        !state.searched -> SearchIntro()
                        else -> ResultList(state, onPaperClick, viewModel::loadMore)
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalResultList(
    state: SearchUiState,
    onPaperClick: (String) -> Unit,
) {
    when {
        state.query.isBlank() ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.search_local_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        state.localResults.isEmpty() ->
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.search_local_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        else ->
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (!state.semanticActive) {
                    item {
                        Text(
                            text = stringResource(R.string.search_semantic_pending),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                itemsIndexed(state.localResults, key = { _, hit -> hit.paper.id.value }) { _, hit ->
                    PaperListItem(
                        paper = hit.paper,
                        onClick = { onPaperClick(hit.paper.id.value) },
                        badge =
                            when (hit.provenance) {
                                Provenance.BOTH ->
                                    PaperBadge(stringResource(R.string.search_badge_both), StatusTone.Machine)
                                Provenance.SEMANTIC ->
                                    PaperBadge(stringResource(R.string.search_badge_semantic), StatusTone.Machine)
                                Provenance.KEYWORD -> null
                            },
                    )
                }
            }
    }
}

@Composable
private fun ResultList(
    state: SearchUiState,
    onPaperClick: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        state.totalResults?.let { total ->
            item {
                Text(
                    text = pluralStringResource(R.plurals.search_result_count, total, total),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        itemsIndexed(state.results, key = { _, paper -> paper.id.value }) { index, paper ->
            PaperListItem(paper = paper, onClick = { onPaperClick(paper.id.value) })
            if (index >= state.results.lastIndex - 5 && state.nextStart != null) {
                onLoadMore()
            }
        }
        if (state.loadingMore) {
            item {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(modifier = Modifier.padding(4.dp)) }
            }
        }
    }
}

@Composable
private fun SearchingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.search_querying_arxiv),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun SearchIntro() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.search_intro_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.search_intro_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun EmptyResults() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.search_no_results),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
