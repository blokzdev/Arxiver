package dev.blokz.arxiver.feature.today

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.claude.RoutineAction
import dev.blokz.arxiver.data.InboxPaper
import dev.blokz.arxiver.feature.claude.DispatchSheet
import dev.blokz.arxiver.feature.organize.OrganizeSheet
import dev.blokz.arxiver.ui.components.EmptyState
import dev.blokz.arxiver.ui.components.SectionHeader
import dev.blokz.arxiver.ui.components.SkeletonList
import dev.blokz.arxiver.ui.components.SwipeablePaperRow
import dev.blokz.arxiver.ui.feedback.FeedbackAction
import dev.blokz.arxiver.ui.feedback.FeedbackMessage
import dev.blokz.arxiver.ui.feedback.LocalFeedbackController
import dev.blokz.arxiver.ui.fixtures.PreviewFixtures
import dev.blokz.arxiver.ui.theme.ArxiverTheme
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
    var organizeIds by remember { mutableStateOf<List<String>?>(null) }
    val scope = rememberCoroutineScope()
    val feedback = LocalFeedbackController.current

    // Every triage swipe gets an undo window (SPEC-UI §3), routed through the app-level feedback host.
    // A save also offers a second step — file the paper into a collection/tag.
    val savedMessage = stringResource(R.string.today_snackbar_saved)
    val dismissedMessage = stringResource(R.string.today_snackbar_dismissed)
    val undoLabel = stringResource(R.string.action_undo)
    val addToLabel = stringResource(R.string.action_add_to_collection)
    LaunchedEffect(triageEvent) {
        val event = triageEvent ?: return@LaunchedEffect
        val saved = event.kind == TriageKind.SAVED
        feedback.show(
            FeedbackMessage(
                text = if (saved) savedMessage else dismissedMessage,
                primary = FeedbackAction(undoLabel) { viewModel.undo(event) },
                secondary = if (saved) FeedbackAction(addToLabel) { organizeIds = listOf(event.paperId) } else null,
            ),
        )
        viewModel.consumeTriageEvent()
    }

    organizeIds?.let { ids ->
        OrganizeSheet(paperIds = ids, onDismiss = { organizeIds = null })
    }

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
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.cd_refresh_inbox))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            // Skeletons convey the first load; the pull indicator is for background/user refresh
            // once papers are present — so we never show two spinners at once.
            isRefreshing = state.syncing && !state.loading,
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
                state.loading -> SkeletonList()
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
        itemsIndexed(scored, key = { _, it -> it.paper.ref.storageId }) { index, item ->
            key(item.paper.ref.storageId) {
                SwipeablePaperRow(
                    paper = item.paper,
                    onClick = { onPaperClick(item.paper.ref.storageId) },
                    onSwipeSave = { onSave(item) },
                    onSwipeDismiss = { onDismiss(item) },
                    score = item.score,
                    // No trailing divider on the final row (unless the rest-list follows).
                    showDivider = index != scored.lastIndex || unscored.isNotEmpty(),
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (scored.isNotEmpty() && unscored.isNotEmpty()) {
            item(key = "header-rest") { SectionHeader(stringResource(R.string.today_more_from_follows)) }
        }
        itemsIndexed(unscored, key = { _, it -> it.paper.ref.storageId }) { index, item ->
            key(item.paper.ref.storageId) {
                SwipeablePaperRow(
                    paper = item.paper,
                    onClick = { onPaperClick(item.paper.ref.storageId) },
                    onSwipeSave = { onSave(item) },
                    onSwipeDismiss = { onDismiss(item) },
                    score = item.score,
                    showDivider = index != unscored.lastIndex,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

private const val RELEVANT_THRESHOLD = 0.55

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
