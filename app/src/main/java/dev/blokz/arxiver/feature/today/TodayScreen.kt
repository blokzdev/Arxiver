package dev.blokz.arxiver.feature.today

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.claude.RoutineAction
import dev.blokz.arxiver.data.InboxPaper
import dev.blokz.arxiver.feature.claude.DispatchSheet
import dev.blokz.arxiver.ui.components.EmptyState
import dev.blokz.arxiver.ui.components.PaperListItem
import dev.blokz.arxiver.ui.components.SectionHeader
import dev.blokz.arxiver.ui.fixtures.PreviewFixtures
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing
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
    val triageEvent by viewModel.triageEvent.collectAsState()
    var weeklyReviewIds by remember { mutableStateOf<List<String>?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Every triage swipe gets an undo window (SPEC-UI §3).
    val savedMessage = stringResource(R.string.today_snackbar_saved)
    val dismissedMessage = stringResource(R.string.today_snackbar_dismissed)
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(triageEvent) {
        val event = triageEvent ?: return@LaunchedEffect
        val result =
            snackbar.showSnackbar(
                message = if (event.kind == TriageKind.SAVED) savedMessage else dismissedMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
        if (result == SnackbarResult.ActionPerformed) viewModel.undo(event)
        viewModel.consumeTriageEvent()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
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
                            modifier = Modifier.padding(end = Spacing.lg),
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
        PullToRefreshBox(
            isRefreshing = state.syncing,
            onRefresh = viewModel::refresh,
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
                        icon = Icons.Outlined.Inbox,
                        actionLabel = stringResource(R.string.today_go_browse),
                        onAction = onGoBrowse,
                    )
                state.items.isEmpty() ->
                    EmptyState(
                        title = stringResource(R.string.today_inbox_zero_title),
                        body = stringResource(R.string.today_inbox_zero_body),
                        icon = Icons.Outlined.TaskAlt,
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
    onSave: (InboxPaper) -> Unit,
    onDismiss: (InboxPaper) -> Unit,
) {
    // SPEC-SEARCH §5: scored items lead under "Likely relevant"; rest follow.
    val (scored, unscored) = items.partition { (it.score ?: 0.0) >= RELEVANT_THRESHOLD }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (scored.isNotEmpty()) {
            item(key = "header-relevant") { SectionHeader(stringResource(R.string.today_likely_relevant)) }
        }
        itemsIndexed(scored, key = { _, it -> it.paper.id.value }) { index, item ->
            key(item.paper.id.value) {
                SwipeableInboxRow(
                    item = item,
                    onClick = { onPaperClick(item.paper.id.value) },
                    onSave = { onSave(item) },
                    onDismiss = { onDismiss(item) },
                    // No trailing divider on the final row (unless the rest-list follows).
                    showDivider = index != scored.lastIndex || unscored.isNotEmpty(),
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (scored.isNotEmpty() && unscored.isNotEmpty()) {
            item(key = "header-rest") { SectionHeader(stringResource(R.string.today_more_from_follows)) }
        }
        itemsIndexed(unscored, key = { _, it -> it.paper.id.value }) { index, item ->
            key(item.paper.id.value) {
                SwipeableInboxRow(
                    item = item,
                    onClick = { onPaperClick(item.paper.id.value) },
                    onSave = { onSave(item) },
                    onDismiss = { onDismiss(item) },
                    showDivider = index != unscored.lastIndex,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

private const val RELEVANT_THRESHOLD = 0.55

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableInboxRow(
    item: InboxPaper,
    onClick: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                when (value) {
                    SwipeToDismissBoxValue.StartToEnd -> {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSave()
                        true
                    }
                    SwipeToDismissBoxValue.EndToStart -> {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                        true
                    }
                    SwipeToDismissBoxValue.Settled -> false
                }
            },
        )

    // SPEC-UI §5: swipe gestures need TalkBack-discoverable equivalents.
    val saveActionLabel = stringResource(R.string.cd_save_paper)
    val dismissActionLabel = stringResource(R.string.cd_dismiss_paper)
    SwipeToDismissBox(
        state = dismissState,
        modifier =
            modifier.semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction(saveActionLabel) {
                            onSave()
                            true
                        },
                        CustomAccessibilityAction(dismissActionLabel) {
                            onDismiss()
                            true
                        },
                    )
            },
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
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            Icons.Filled.Close,
                            Alignment.CenterEnd,
                        )
                }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(color)
                        .padding(horizontal = Spacing.xl),
                contentAlignment = alignment,
            ) {
                Icon(icon, contentDescription = null)
            }
        },
    ) {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            PaperListItem(paper = item.paper, onClick = onClick, score = item.score, showDivider = showDivider)
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun InboxListPreview() {
    ArxiverTheme {
        InboxList(
            items = PreviewFixtures.inboxPapers,
            onPaperClick = {},
            onSave = {},
            onDismiss = {},
        )
    }
}
