package dev.blokz.arxiver.feature.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.claude.RoutineAction
import dev.blokz.arxiver.data.InboxPaper
import dev.blokz.arxiver.feature.claude.DispatchSheet
import dev.blokz.arxiver.ui.components.PaperListItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onPaperClick: (String) -> Unit,
    onGoBrowse: () -> Unit,
    onOpenRoutines: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var weeklyReviewIds by remember { mutableStateOf<List<String>?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_today)) },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch { weeklyReviewIds = viewModel.weeklyReviewSelection() }
                        },
                    ) {
                        Icon(Icons.Filled.AutoAwesome, stringResource(R.string.cd_weekly_review))
                    }
                    if (state.syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Filled.Refresh, stringResource(R.string.cd_refresh_inbox))
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.settings_title))
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
                !state.hasFollows ->
                    EmptyState(
                        title = stringResource(R.string.today_no_follows_title),
                        body = stringResource(R.string.today_no_follows_body),
                        actionLabel = stringResource(R.string.today_go_browse),
                        onAction = onGoBrowse,
                    )
                state.items.isEmpty() ->
                    EmptyState(
                        title = stringResource(R.string.today_inbox_zero_title),
                        body = stringResource(R.string.today_inbox_zero_body),
                        actionLabel = null,
                        onAction = {},
                    )
                else ->
                    InboxList(
                        items = state.items,
                        onPaperClick = onPaperClick,
                        onSave = viewModel::save,
                        onDismiss = viewModel::dismiss,
                    )
            }
        }
    }

    TodayDispatchHost(
        weeklyReviewIds = weeklyReviewIds,
        onDismiss = { weeklyReviewIds = null },
        onOpenRoutines = onOpenRoutines,
    )
}

@Composable
private fun TodayDispatchHost(
    weeklyReviewIds: List<String>?,
    onDismiss: () -> Unit,
    onOpenRoutines: () -> Unit,
) {
    weeklyReviewIds?.let { ids ->
        DispatchSheet(
            paperIds = ids,
            onDismiss = onDismiss,
            onGoToRoutines = {
                onDismiss()
                onOpenRoutines()
            },
            presetAction = RoutineAction.WEEKLY_REVIEW,
        )
    }
}

@Composable
private fun InboxList(
    items: List<InboxPaper>,
    onPaperClick: (String) -> Unit,
    onSave: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    // SPEC-SEARCH §5: scored items lead under "Likely relevant"; rest follow.
    val (scored, unscored) = items.partition { (it.score ?: 0.0) >= RELEVANT_THRESHOLD }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (scored.isNotEmpty()) {
            item(key = "header-relevant") { SectionHeader(stringResource(R.string.today_likely_relevant)) }
        }
        items(scored, key = { it.paper.id.value }) { item ->
            key(item.paper.id.value) {
                SwipeableInboxRow(
                    item = item,
                    onClick = { onPaperClick(item.paper.id.value) },
                    onSave = { onSave(item.paper.id.value) },
                    onDismiss = { onDismiss(item.paper.id.value) },
                )
            }
        }
        if (scored.isNotEmpty() && unscored.isNotEmpty()) {
            item(key = "header-rest") { SectionHeader(stringResource(R.string.today_more_from_follows)) }
        }
        items(unscored, key = { it.paper.id.value }) { item ->
            key(item.paper.id.value) {
                SwipeableInboxRow(
                    item = item,
                    onClick = { onPaperClick(item.paper.id.value) },
                    onSave = { onSave(item.paper.id.value) },
                    onDismiss = { onDismiss(item.paper.id.value) },
                )
            }
        }
    }
}

private const val RELEVANT_THRESHOLD = 0.55

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableInboxRow(
    item: InboxPaper,
    onClick: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                when (value) {
                    SwipeToDismissBoxValue.StartToEnd -> {
                        onSave()
                        true
                    }
                    SwipeToDismissBoxValue.EndToStart -> {
                        onDismiss()
                        true
                    }
                    SwipeToDismissBoxValue.Settled -> false
                }
            },
        )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val (color, icon, alignment) =
                when (dismissState.dismissDirection) {
                    SwipeToDismissBoxValue.StartToEnd ->
                        Triple(
                            MaterialTheme.colorScheme.primaryContainer,
                            Icons.Filled.BookmarkAdd,
                            Alignment.CenterStart,
                        )
                    else ->
                        Triple(
                            MaterialTheme.colorScheme.surfaceVariant,
                            Icons.Filled.Close,
                            Alignment.CenterEnd,
                        )
                }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(color)
                        .padding(horizontal = 24.dp),
                contentAlignment = alignment,
            ) {
                Icon(icon, contentDescription = null)
            }
        },
    ) {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            PaperListItem(paper = item.paper, onClick = onClick, score = item.score)
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (actionLabel != null) {
            Row(modifier = Modifier.padding(top = 16.dp)) {
                androidx.compose.material3.FilledTonalButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}
