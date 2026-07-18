package dev.blokz.arxiver.feature.today

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.claude.RoutineAction
import dev.blokz.arxiver.core.database.entity.ReadingPositionEntity
import dev.blokz.arxiver.core.model.ArxivTaxonomy
import dev.blokz.arxiver.data.ContinueReadingUi
import dev.blokz.arxiver.data.EmergingAreaUi
import dev.blokz.arxiver.data.InboxPaper
import dev.blokz.arxiver.data.RecShelfResult
import dev.blokz.arxiver.data.s2.browserFallbackUrl
import dev.blokz.arxiver.data.s2.bylineText
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
import dev.blokz.arxiver.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onPaperClick: (String) -> Unit,
    onGoBrowse: () -> Unit,
    onOpenRoutines: () -> Unit,
    onOpenSettings: () -> Unit,
    onResumeReading: (String, String) -> Unit,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val recShelf by viewModel.recShelf.collectAsState()
    val recConsentAvailable by viewModel.recShelfConsentAvailable.collectAsState()
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
        // Stable UiAutomator handle for the PP.3 startup/frame benchmarks (paired with
        // testTagsAsResourceId=true at the app root so By.res("today_screen") resolves on device).
        modifier = Modifier.testTag("today_screen"),
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
                // Inbox-zero no longer takes over the whole surface (PRS.3): InboxList renders the
                // "all caught up" card as an in-list item so Continue-reading and the recommendations
                // shelf survive the daily triage-to-zero moment — the shelf's best user.
                else ->
                    InboxList(
                        items = state.items,
                        threshold = state.relevantThreshold,
                        emergingAreas = state.emergingAreas,
                        continueReading = state.continueReading,
                        recShelf = recShelf,
                        recConsentAvailable = recConsentAvailable,
                        onPaperClick = onPaperClick,
                        onResumeReading = onResumeReading,
                        onSave = viewModel::save,
                        onDismiss = viewModel::dismiss,
                        onVote = viewModel::relevanceVote,
                        onFetchRec = viewModel::fetchRecommendations,
                        onRefreshRec = viewModel::refreshRecommendations,
                        onHideRec = viewModel::hideRecommendation,
                        onEnableAutoRec = viewModel::enableAutoRefresh,
                        onDismissAutoRec = viewModel::dismissAutoRefreshInvite,
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
    threshold: Double,
    emergingAreas: List<EmergingAreaUi>,
    continueReading: List<ContinueReadingUi>,
    recShelf: RecShelfUiState,
    recConsentAvailable: Boolean,
    onPaperClick: (String) -> Unit,
    onResumeReading: (String, String) -> Unit,
    onSave: (InboxPaper) -> Unit,
    onDismiss: (InboxPaper) -> Unit,
    onVote: (InboxPaper, Boolean) -> Unit,
    onFetchRec: () -> Unit,
    onRefreshRec: () -> Unit,
    onHideRec: (String) -> Unit,
    onEnableAutoRec: () -> Unit,
    onDismissAutoRec: () -> Unit,
) {
    val itemsById = remember(items) { items.associateBy { it.paper.ref.storageId } }
    // SPEC-SEARCH §5: scored items lead under "Likely relevant"; rest follow.
    // Top-k by score above the calibrated floor (P5.3, Co-Founder decision): a stable, capped section — never
    // a flood on a generous day, never junk-padded on a quiet one. Items arrive score-ordered from the DAO.
    val scoredIds =
        items.asSequence()
            .filter { (it.score ?: 0.0) >= threshold }
            .sortedByDescending { it.score ?: 0.0 }
            .take(RELEVANT_TOP_K)
            .map { it.paper.ref.storageId }
            .toSet()
    val (scored, unscored) = items.partition { it.paper.ref.storageId in scoredIds }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Inbox-zero as an in-list card (PRS.3): the "all caught up" message no longer replaces the
        // whole surface, so the shelf + Continue-reading below it survive an empty inbox.
        if (items.isEmpty()) {
            item(key = "inbox-zero-card") { InboxZeroCard() }
        }
        // "Continue reading" (P-Read) — papers you genuinely scrolled into and haven't finished, at the very top.
        // Collapsible-by-absence: renders only when something honestly qualifies (no "start reading!" guilt-CTA).
        if (continueReading.isNotEmpty()) {
            item(key = "header-continue") { SectionHeader(stringResource(R.string.today_continue_heading)) }
            continueReading.forEach { card ->
                item(key = "continue-${card.paper.ref.storageId}") {
                    ContinueReadingRow(card = card, onResumeReading = onResumeReading)
                }
            }
        }
        // "Emerging in your areas" (P-Discover2 PD.3b) — a calm, opt-in, collapsible-by-absence shelf at the top.
        // Empty is the common, honest state (shown only when the opt-in is on AND an area cleared the emergence bar).
        if (emergingAreas.isNotEmpty()) {
            item(key = "header-emerging") { SectionHeader(stringResource(R.string.today_emerging_heading)) }
            emergingAreas.forEach { area ->
                item(key = "emerging-${area.category}") {
                    EmergingAreaRow(area = area, itemsById = itemsById, onPaperClick = onPaperClick)
                }
            }
        }
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
                    vote = item.vote,
                    onVote = { up -> onVote(item, up) },
                    // No trailing divider on the final row (unless the rest-list follows).
                    showDivider = index != scored.lastIndex || unscored.isNotEmpty(),
                    modifier = Modifier.animateItem(),
                )
            }
        }
        // "Recommended for you" (P-RecShelf) — AFTER the user's own scored triage, BEFORE the follows
        // firehose; never crowds out "Likely relevant". Collapses to nothing when there's no seed.
        recShelfSection(
            state = recShelf,
            consentAvailable = recConsentAvailable,
            onFetch = onFetchRec,
            onRefresh = onRefreshRec,
            onHide = onHideRec,
            onOpenPaper = onPaperClick,
            onEnableAuto = onEnableAutoRec,
            onDismissAuto = onDismissAutoRec,
        )
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
                    vote = item.vote,
                    onVote = { up -> onVote(item, up) },
                    showDivider = index != unscored.lastIndex,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

private const val RELEVANT_TOP_K = 10

/** The in-list "all caught up" card (PRS.3) — compact, so the shelf below it stays visible at inbox-zero. */
@Composable
private fun InboxZeroCard() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
    ) {
        Icon(
            Icons.Outlined.TaskAlt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.padding(start = Spacing.md)) {
            Text(
                text = stringResource(R.string.today_inbox_zero_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.today_inbox_zero_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The "Recommended for you" shelf section (PRS.3), rendered as LazyColumn items so it flows inline with
 * the feed. Tap-gated: [RecShelfUiState.Idle] shows a disclosed invitation, [onFetch] is the only egress
 * trigger, the result is memoized. Collapses to zero items when [RecShelfUiState.Hidden] (no seedable
 * positive) or when a Ready list has been fully hidden — no lonely header.
 */
private fun LazyListScope.recShelfSection(
    state: RecShelfUiState,
    consentAvailable: Boolean,
    onFetch: () -> Unit,
    onRefresh: () -> Unit,
    onHide: (String) -> Unit,
    onOpenPaper: (String) -> Unit,
    onEnableAuto: () -> Unit,
    onDismissAuto: () -> Unit,
) {
    // Nothing seedable → the shelf is simply absent (cold-start silence).
    if (state is RecShelfUiState.Hidden) return
    // The two "render nothing" terminals in ONE place: a Ready list emptied by "Not interested" on every
    // row, and a NoSeeds result (defensive — the VM normally converts empty seeds to Hidden before any
    // fetch, but guarding here keeps the header from appearing alone if that ever changes).
    if (state is RecShelfUiState.Done) {
        val r = state.result
        if (r is RecShelfResult.NoSeeds || (r is RecShelfResult.Ready && r.hits.isEmpty())) return
    }

    item(key = "header-recshelf") {
        // Refresh is offered once something has been fetched (a terminal Done that isn't NotRecommendable,
        // or a Cached render) — never on the Idle invite or a mid-fetch Loading.
        val showRefresh =
            (state is RecShelfUiState.Done && state.result !is RecShelfResult.NotRecommendable) ||
                state is RecShelfUiState.Cached
        RecShelfHeader(showRefresh = showRefresh, onRefresh = onRefresh)
    }

    when (state) {
        is RecShelfUiState.Idle -> {
            item(key = "recshelf-invite") { RecShelfInviteCard(seedCount = state.seedCount, onFetch = onFetch) }
            // The one-time auto-refresh invitation (PRS.4) — only while auto is OFF and not yet acted on.
            if (consentAvailable) {
                item(key = "recshelf-consent") {
                    RecShelfAutoConsentCard(onEnable = onEnableAuto, onDismiss = onDismissAuto)
                }
            }
        }
        is RecShelfUiState.Loading ->
            item(key = "recshelf-loading") { RecShelfLoadingRow() }
        is RecShelfUiState.Cached -> {
            item(
                key = "recshelf-staleness",
            ) { RecShelfStaleness(fetchedAtMs = state.fetchedAtMs, refreshing = state.refreshing) }
            items(state.hits, key = { "recshelf-${it.s2PaperId}" }) { hit ->
                RecShelfRow(hit = hit, onOpenPaper = onOpenPaper, onHide = onHide)
            }
        }
        is RecShelfUiState.Done ->
            when (val result = state.result) {
                is RecShelfResult.Ready -> {
                    item(key = "recshelf-provenance") { RecShelfProvenance() }
                    items(result.hits, key = { "recshelf-${it.s2PaperId}" }) { hit ->
                        RecShelfRow(hit = hit, onOpenPaper = onOpenPaper, onHide = onHide)
                    }
                }
                is RecShelfResult.Error ->
                    item(key = "recshelf-error") { RecShelfErrorCard(error = result.error, onRetry = onFetch) }
                RecShelfResult.NotRecommendable ->
                    item(key = "recshelf-note") { RecShelfNote(stringResource(R.string.recshelf_not_recommendable)) }
                RecShelfResult.EmptyNoneReturned ->
                    item(key = "recshelf-note") { RecShelfNote(stringResource(R.string.recshelf_empty_none)) }
                RecShelfResult.EmptyAllLocal ->
                    item(key = "recshelf-note") { RecShelfNote(stringResource(R.string.recshelf_empty_all_local)) }
                // NoSeeds can only arise if the library emptied mid-session; treat as silence.
                RecShelfResult.NoSeeds -> Unit
            }
        RecShelfUiState.Hidden -> Unit // handled above
    }
}

@Composable
private fun RecShelfHeader(
    showRefresh: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SectionHeader(
            text = stringResource(R.string.recshelf_heading),
            // The section title is a heading landmark for TalkBack navigation.
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (showRefresh) {
            IconButton(onClick = onRefresh, modifier = Modifier.padding(end = Spacing.sm)) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cd_recshelf_refresh))
            }
        }
    }
}

@Composable
private fun RecShelfInviteCard(
    seedCount: Int,
    onFetch: () -> Unit,
) {
    // The disclosure IS the pre-tap contract: the count shown is EXACTLY what the tap sends.
    val disclosure = pluralStringResource(R.plurals.recshelf_disclosure, seedCount, seedCount)
    val invite = pluralStringResource(R.plurals.recshelf_invite, seedCount, seedCount)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = stringResource(R.string.cd_recshelf_fetch), onClick = onFetch)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                .semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = invite,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = Spacing.sm),
            )
        }
        Text(
            text = disclosure,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

@Composable
private fun RecShelfLoadingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                .semantics(mergeDescendants = true) {},
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        Text(
            text = stringResource(R.string.recshelf_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.md),
        )
    }
}

@Composable
private fun RecShelfProvenance() {
    Text(
        text = stringResource(R.string.recshelf_provenance),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    )
}

/** Auto-refresh mode caption: an honest "Updated Nh ago" staleness label, with a subtle spinner while refreshing. */
@Composable
private fun RecShelfStaleness(
    fetchedAtMs: Long,
    refreshing: Boolean,
) {
    val label = recShelfStalenessLabel(fetchedAtMs)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    ) {
        if (refreshing) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(12.dp).padding(end = Spacing.xs))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Updated just now / Nh ago / N days ago" from the last successful fetch timestamp. */
@Composable
private fun recShelfStalenessLabel(fetchedAtMs: Long): String {
    val ageMs = (System.currentTimeMillis() - fetchedAtMs).coerceAtLeast(0)
    val hours = ageMs / (60 * 60 * 1000)
    val days = hours / 24
    return when {
        hours < 1 -> stringResource(R.string.recshelf_updated_recent)
        days < 1 -> pluralStringResource(R.plurals.recshelf_updated_hours, hours.toInt(), hours.toInt())
        else -> pluralStringResource(R.plurals.recshelf_updated_days, days.toInt(), days.toInt())
    }
}

/** The one-time auto-refresh invitation (PRS.4) — cadence-honest, dismissible; the opt-in also lives in Settings. */
@Composable
private fun RecShelfAutoConsentCard(
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.recshelf_auto_consent),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            TextButton(onClick = onEnable) { Text(stringResource(R.string.recshelf_auto_enable)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.recshelf_auto_dismiss)) }
        }
    }
}

@Composable
private fun RecShelfNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
    )
}

@Composable
private fun RecShelfErrorCard(
    error: dev.blokz.arxiver.core.common.AppError,
    onRetry: () -> Unit,
) {
    // Timeout (Upstream(null)) and 5xx get a NEUTRAL "couldn't reach"; Offline and 429 get their own copy.
    val message =
        when {
            error is dev.blokz.arxiver.core.common.AppError.Offline -> stringResource(R.string.recshelf_error_offline)
            error is dev.blokz.arxiver.core.common.AppError.Upstream && error.httpCode == 429 ->
                stringResource(R.string.recshelf_error_busy)
            else -> stringResource(R.string.recshelf_error_generic)
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = stringResource(R.string.cd_recshelf_retry), onClick = onRetry)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * One recommendation row. a11y (PRS.3 fix over the PDM sheet's CD-override): merge the title+byline so
 * TalkBack SPEAKS them, and put the open intent on the clickable's [onClickLabel] instead of replacing
 * the text with a contentDescription. arXiv hits open in-app (the destination fetch-persists); non-arXiv
 * hits open in the browser via the shipped doi.org → OA PDF → S2-landing chain.
 */
@Composable
private fun RecShelfRow(
    hit: dev.blokz.arxiver.data.DiscoverHit,
    onOpenPaper: (String) -> Unit,
    onHide: (String) -> Unit,
) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val inApp = hit.arxivId != null
    val openLabel =
        if (inApp) stringResource(R.string.recshelf_open_in_app) else stringResource(R.string.recshelf_open_browser)
    val hideCd = stringResource(R.string.cd_recshelf_hide, hit.title)
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = openLabel) {
                    val arxivId = hit.arxivId
                    if (arxivId != null) {
                        onOpenPaper(arxivId.value)
                    } else {
                        uriHandler.openUri(hit.browserFallbackUrl())
                    }
                }
                .padding(start = Spacing.lg, top = Spacing.sm, bottom = Spacing.sm)
                .semantics(mergeDescendants = true) {},
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hit.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val byline = hit.bylineText()
            if (byline.isNotBlank()) {
                Text(
                    text = byline,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!inApp) {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.sm),
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.cd_recshelf_more, hit.title),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.recshelf_hide)) },
                    onClick = {
                        menuOpen = false
                        onHide(hit.s2PaperId)
                    },
                    modifier = Modifier.semantics { contentDescription = hideCd },
                )
            }
        }
    }
}

/**
 * One "Continue reading" row (P-Read): a paper you genuinely scrolled into, with a subtle POSITION line (never
 * "percent read" — a scroll fraction over a body with refs/appendix can't assert reading). Tapping resumes at
 * the recorded surface; each reader restores its own precise position on open. TalkBack gets equal-strength
 * position language.
 */
@Composable
private fun ContinueReadingRow(
    card: ContinueReadingUi,
    onResumeReading: (String, String) -> Unit,
) {
    val percent = (card.fraction * 100).roundToInt().coerceIn(0, 100)
    val surfaceLabel =
        if (card.surface == ReadingPositionEntity.SURFACE_HTML) {
            stringResource(R.string.today_continue_html)
        } else {
            stringResource(R.string.today_continue_pdf)
        }
    val cd = stringResource(R.string.today_continue_cd, card.paper.title, percent, surfaceLabel)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onResumeReading(card.paper.ref.storageId, card.surface) }
                .semantics(mergeDescendants = true) { contentDescription = cd }
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        Text(
            text = card.paper.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
        )
        Text(
            text = stringResource(R.string.today_continue_position, percent, surfaceLabel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

/**
 * One "Emerging in your areas" row (P-Discover2 PD.3b): a human area name + honest "more active than usual" copy +
 * a few of the driving papers (resolved from the already-observed inbox — no extra query). Never "hot"/"trending"/a
 * count badge, never implies global popularity.
 */
@Composable
private fun EmergingAreaRow(
    area: EmergingAreaUi,
    itemsById: Map<String, InboxPaper>,
    onPaperClick: (String) -> Unit,
) {
    val name = ArxivTaxonomy.byCode(area.category)?.name ?: area.category
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.today_emerging_area, name),
            style = MaterialTheme.typography.titleSmall,
        )
        area.drivingPaperIds.mapNotNull { itemsById[it] }.forEach { paper ->
            Text(
                text = paper.paper.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPaperClick(paper.paper.ref.storageId) }
                        .padding(top = Spacing.xs),
            )
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
            threshold = LEGACY_RELEVANT_THRESHOLD,
            emergingAreas = emptyList(),
            continueReading =
                PreviewFixtures.papers.take(1).map {
                    ContinueReadingUi(
                        paper = it,
                        surface = ReadingPositionEntity.SURFACE_HTML,
                        fraction = 0.34f,
                        updatedAt = 0,
                    )
                },
            recShelf = RecShelfUiState.Idle(seedCount = 12),
            recConsentAvailable = true,
            onPaperClick = {},
            onResumeReading = { _, _ -> },
            onSave = {},
            onDismiss = {},
            onVote = { _, _ -> },
            onFetchRec = {},
            onRefreshRec = {},
            onHideRec = {},
            onEnableAutoRec = {},
            onDismissAutoRec = {},
        )
    }
}

@Preview(showBackground = true, name = "RecShelf — Ready")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "RecShelf — Ready (dark)")
@Composable
private fun RecShelfReadyPreview() {
    ArxiverTheme {
        LazyColumn {
            recShelfSection(
                state =
                    RecShelfUiState.Done(
                        RecShelfResult.Ready(
                            PreviewFixtures.papers.take(3).map { p ->
                                dev.blokz.arxiver.data.DiscoverHit(
                                    s2PaperId = "s2-${p.ref.storageId}",
                                    title = p.title,
                                    authors = p.authors.take(3),
                                    year = 2026,
                                    venue = "NeurIPS",
                                    abstract = p.abstract,
                                    arxivId = p.ref.arxivIdOrNull,
                                    doi = if (p.ref.arxivIdOrNull == null) "10.1101/2026.01.01.1" else null,
                                    openAccessPdfUrl = null,
                                )
                            },
                        ),
                    ),
                consentAvailable = false,
                onFetch = {},
                onRefresh = {},
                onHide = {},
                onOpenPaper = {},
                onEnableAuto = {},
                onDismissAuto = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "RecShelf — Idle + auto consent")
@Composable
private fun RecShelfIdlePreview() {
    ArxiverTheme {
        LazyColumn {
            recShelfSection(
                state = RecShelfUiState.Idle(seedCount = 12),
                consentAvailable = true,
                onFetch = {},
                onRefresh = {},
                onHide = {},
                onOpenPaper = {},
                onEnableAuto = {},
                onDismissAuto = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "RecShelf — Cached (auto)")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "RecShelf — Cached (dark)")
@Composable
private fun RecShelfCachedPreview() {
    ArxiverTheme {
        LazyColumn {
            recShelfSection(
                state =
                    RecShelfUiState.Cached(
                        hits =
                            PreviewFixtures.papers.take(2).map { p ->
                                dev.blokz.arxiver.data.DiscoverHit(
                                    s2PaperId = "s2-${p.ref.storageId}",
                                    title = p.title,
                                    authors = p.authors.take(3),
                                    year = 2026,
                                    venue = "ICML",
                                    abstract = p.abstract,
                                    arxivId = p.ref.arxivIdOrNull,
                                    doi = null,
                                    openAccessPdfUrl = null,
                                )
                            },
                        fetchedAtMs = System.currentTimeMillis() - 3 * 60 * 60 * 1000,
                        refreshing = false,
                    ),
                consentAvailable = false,
                onFetch = {},
                onRefresh = {},
                onHide = {},
                onOpenPaper = {},
                onEnableAuto = {},
                onDismissAuto = {},
            )
        }
    }
}
