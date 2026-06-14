package dev.blokz.arxiver.feature.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.ui.components.ErrorState
import dev.blokz.arxiver.ui.components.PaperListItem
import dev.blokz.arxiver.ui.components.SkeletonList
import dev.blokz.arxiver.ui.components.SkeletonPaperItem
import dev.blokz.arxiver.ui.theme.Spacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryFeedScreen(
    onBack: () -> Unit,
    onPaperClick: (String) -> Unit,
    viewModel: CategoryFeedViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            state.code,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading && state.papers.isNotEmpty(),
            onRefresh = viewModel::refresh,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            when {
                state.loading && state.papers.isEmpty() -> LoadingState()
                state.error != null && state.papers.isEmpty() ->
                    ErrorState(error = state.error, onRetry = viewModel::refresh)
                else -> PaperList(state, onPaperClick, viewModel::loadMore)
            }
        }
    }
}

@Composable
private fun PaperList(
    state: CategoryFeedUiState,
    onPaperClick: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showScrollToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 12 }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(state.papers, key = { _, paper -> paper.id.value }) { index, paper ->
                PaperListItem(
                    paper = paper,
                    onClick = { onPaperClick(paper.id.value) },
                    showDivider = index != state.papers.lastIndex,
                )
                // Infinite scroll: kick off the next page as the tail approaches.
                if (index >= state.papers.lastIndex - 5 && state.nextStart != null) {
                    onLoadMore()
                }
            }
            if (state.loadingMore) {
                item { SkeletonPaperItem() }
            }
        }
        AnimatedVisibility(
            visible = showScrollToTop,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Spacing.lg),
        ) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
            ) {
                Icon(Icons.Filled.ArrowUpward, stringResource(R.string.cd_scroll_to_top))
            }
        }
    }
}

@Composable
private fun LoadingState() {
    // Content-shaped skeletons (SPEC-UI §4); the rate-limit note stays —
    // informative, never error-styled.
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            text = stringResource(R.string.feed_loading_queued),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
        )
        SkeletonList()
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CategoryFeedPreview() {
    dev.blokz.arxiver.ui.theme.ArxiverTheme {
        PaperList(
            state =
                CategoryFeedUiState(
                    code = "cs.LG",
                    title = "Machine Learning",
                    papers = dev.blokz.arxiver.ui.fixtures.PreviewFixtures.papers,
                ),
            onPaperClick = {},
            onLoadMore = {},
        )
    }
}
