package dev.blokz.arxiver.feature.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.search.Provenance
import dev.blokz.arxiver.ui.components.EmptyState
import dev.blokz.arxiver.ui.components.ErrorState
import dev.blokz.arxiver.ui.components.PaperBadge
import dev.blokz.arxiver.ui.components.PaperListItem
import dev.blokz.arxiver.ui.components.SkeletonList
import dev.blokz.arxiver.ui.components.SkeletonPaperItem
import dev.blokz.arxiver.ui.components.StatusTone
import dev.blokz.arxiver.ui.fixtures.PreviewFixtures
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing

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
            // Pill search field (deliberately not M3 SearchBar — its
            // full-screen expansion fights the tabbed result layout).
            TextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Filled.Close, stringResource(R.string.cd_clear_query))
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.submit() }),
            )

            var tab by rememberSaveable { mutableIntStateOf(0) }
            SingleChoiceSegmentedButtonRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            ) {
                SegmentedButton(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.search_tab_library)) }
                SegmentedButton(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(R.string.search_tab_arxiv)) }
            }

            if (tab == 0) {
                LocalResultList(state, onPaperClick)
            } else {
                when {
                    state.searching -> SearchingState()
                    state.error != null -> ErrorState(error = state.error, onRetry = viewModel::submit)
                    state.searched && state.results.isEmpty() ->
                        EmptyState(
                            title = stringResource(R.string.search_no_results),
                            body = stringResource(R.string.search_intro_body),
                            icon = Icons.Outlined.SearchOff,
                        )
                    !state.searched ->
                        EmptyState(
                            title = stringResource(R.string.search_intro_title),
                            body = stringResource(R.string.search_intro_body),
                            icon = Icons.Filled.Search,
                        )
                    else -> ResultList(state, onPaperClick, viewModel::loadMore)
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
            EmptyState(
                title = stringResource(R.string.search_tab_library),
                body = stringResource(R.string.search_local_intro),
                icon = Icons.Filled.Search,
            )
        state.localResults.isEmpty() ->
            EmptyState(
                title = stringResource(R.string.search_local_no_results),
                body = stringResource(R.string.search_local_intro),
                icon = Icons.Outlined.SearchOff,
            )
        else ->
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (!state.semanticActive) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.small,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                        ) {
                            Text(
                                text = stringResource(R.string.search_semantic_pending),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(Spacing.sm),
                            )
                        }
                    }
                }
                itemsIndexed(state.localResults, key = { _, hit -> hit.paper.id.value }) { index, hit ->
                    PaperListItem(
                        paper = hit.paper,
                        onClick = { onPaperClick(hit.paper.id.value) },
                        showDivider = index != state.localResults.lastIndex,
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
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )
            }
        }
        itemsIndexed(state.results, key = { _, paper -> paper.id.value }) { index, paper ->
            PaperListItem(
                paper = paper,
                onClick = { onPaperClick(paper.id.value) },
                showDivider = index != state.results.lastIndex,
            )
            if (index >= state.results.lastIndex - 5 && state.nextStart != null) {
                onLoadMore()
            }
        }
        if (state.loadingMore) {
            item { SkeletonPaperItem() }
        }
    }
}

@Composable
private fun SearchingState() {
    // Content-shaped skeletons with the rate-limit-aware caption kept.
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.search_querying_arxiv),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
        )
        SkeletonList(itemCount = 6)
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LocalResultsPreview() {
    ArxiverTheme {
        LocalResultList(
            state =
                SearchUiState(
                    query = "diffusion models",
                    semanticActive = true,
                    localResults =
                        PreviewFixtures.papers.mapIndexed { i, paper ->
                            LocalHit(
                                paper = paper,
                                score = 0.9 - i * 0.1,
                                provenance = if (i == 0) Provenance.BOTH else Provenance.KEYWORD,
                            )
                        },
                ),
            onPaperClick = {},
        )
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LocalResultsEmptyPreview() {
    ArxiverTheme {
        LocalResultList(state = SearchUiState(query = ""), onPaperClick = {})
    }
}
