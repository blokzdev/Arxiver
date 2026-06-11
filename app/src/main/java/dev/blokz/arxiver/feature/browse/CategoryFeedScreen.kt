package dev.blokz.arxiver.feature.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.ui.components.PaperListItem

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
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            when {
                state.loading -> LoadingState()
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
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(state.papers, key = { _, paper -> paper.id.value }) { index, paper ->
            PaperListItem(paper = paper, onClick = { onPaperClick(paper.id.value) })
            // Infinite scroll: kick off the next page as the tail approaches.
            if (index >= state.papers.lastIndex - 5 && state.nextStart != null) {
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
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.feed_loading_queued),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
internal fun ErrorState(
    error: AppError?,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text =
                when (error) {
                    is AppError.Offline -> stringResource(R.string.error_offline)
                    is AppError.RateLimited -> stringResource(R.string.error_rate_limited)
                    else -> stringResource(R.string.error_generic)
                },
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}
