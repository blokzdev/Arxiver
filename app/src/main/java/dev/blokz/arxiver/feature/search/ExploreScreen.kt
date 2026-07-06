package dev.blokz.arxiver.feature.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.model.ArxivCategory
import dev.blokz.arxiver.core.model.ArxivTaxonomy
import dev.blokz.arxiver.core.network.arxiv.ArxivQuery
import dev.blokz.arxiver.core.network.arxiv.SearchFilter
import dev.blokz.arxiver.core.search.Provenance
import dev.blokz.arxiver.data.CategoryWithFollowState
import dev.blokz.arxiver.feature.browse.BrowseViewModel
import dev.blokz.arxiver.feature.browse.CategoryGroup
import dev.blokz.arxiver.ui.components.EmptyState
import dev.blokz.arxiver.ui.components.ErrorState
import dev.blokz.arxiver.ui.components.PaperBadge
import dev.blokz.arxiver.ui.components.SelectionTopBar
import dev.blokz.arxiver.ui.components.SkeletonList
import dev.blokz.arxiver.ui.components.SkeletonPaperItem
import dev.blokz.arxiver.ui.components.StatusChip
import dev.blokz.arxiver.ui.components.StatusTone
import dev.blokz.arxiver.ui.components.SwipeablePaperRow
import dev.blokz.arxiver.ui.components.rememberSelectionState
import dev.blokz.arxiver.ui.feedback.FeedbackAction
import dev.blokz.arxiver.ui.feedback.FeedbackMessage
import dev.blokz.arxiver.ui.feedback.LocalFeedbackController
import dev.blokz.arxiver.ui.fixtures.PreviewFixtures
import dev.blokz.arxiver.ui.theme.ArxiverMotion
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing

/**
 * Explore (P-Chat PC.2) — the merged discovery surface: one search field over two scopes
 * (Library live-hybrid | arXiv online-submit) with the category taxonomy as the Library
 * resting state (blank query), reached after Search+Browse were folded into one tab. Reuses
 * the two existing ViewModels unchanged; the taxonomy is [BrowseViewModel]'s grouped feed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    onPaperClick: (String) -> Unit,
    onCategoryClick: (code: String, name: String) -> Unit,
    onOpenRoutines: () -> Unit = {},
    resetToLibrary: Boolean = false,
    viewModel: SearchViewModel = hiltViewModel(),
    browseViewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val browseState by browseViewModel.uiState.collectAsState()
    // Group expansion is view state (plain remember, CS open by default) — mirrors the old
    // BrowseScreen; process-death restoration is a recorded backlog item, unchanged here.
    val expandedGroups = remember { mutableStateMapOf("Computer Science" to true) }
    val selection = rememberSelectionState()
    val feedback = LocalFeedbackController.current
    var organizeIds by remember { mutableStateOf<List<String>?>(null) }
    var showDispatch by remember { mutableStateOf(false) }
    var showSourceFollow by remember { mutableStateOf(false) }

    BackHandler(enabled = selection.isActive) { selection.clear() }

    val savedMessage = stringResource(R.string.today_snackbar_saved)
    val addToLabel = stringResource(R.string.action_add_to_collection)
    val saveOne: (String) -> Unit = { id ->
        viewModel.save(id)
        feedback.show(
            FeedbackMessage(
                text = savedMessage,
                secondary = FeedbackAction(addToLabel) { organizeIds = listOf(id) },
            ),
        )
    }

    Scaffold(
        topBar = {
            if (selection.isActive) {
                SelectionTopBar(count = selection.count, onClear = { selection.clear() }) {
                    IconButton(onClick = { organizeIds = selection.selected.toList() }) {
                        Icon(Icons.Filled.CreateNewFolder, stringResource(R.string.cd_add_to_organize))
                    }
                    IconButton(
                        onClick = {
                            val ids = selection.selected.toList()
                            viewModel.saveAll(ids)
                            feedback.show(
                                FeedbackMessage(
                                    text = savedMessage,
                                    secondary = FeedbackAction(addToLabel) { organizeIds = ids },
                                ),
                            )
                            selection.clear()
                        },
                    ) {
                        Icon(Icons.Filled.BookmarkAdd, stringResource(R.string.cd_save_selected))
                    }
                    IconButton(onClick = { showDispatch = true }) {
                        Icon(Icons.Filled.AutoAwesome, stringResource(R.string.cd_send_to_claude))
                    }
                }
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.nav_explore)) },
                    actions = {
                        IconButton(onClick = { showSourceFollow = true }) {
                            Icon(Icons.Filled.RssFeed, stringResource(R.string.cd_follow_sources))
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            // Scope toggle state is hoisted ABOVE the field so the IME Search action can gate
            // on it (PC.2): submit() builds an arXiv network query, so firing it on the Library
            // scope would leak a network call the user never asked for.
            var tab by rememberSaveable { mutableIntStateOf(0) }
            // The Today "no follows" CTA routes here to reach the taxonomy — force the Library
            // resting state (scope 0 + blank query) once so a stale query/scope can't hide it.
            LaunchedEffect(resetToLibrary) {
                if (resetToLibrary) {
                    tab = 0
                    viewModel.onQueryChange("")
                }
            }

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
                // Library scope is live-debounced with no submit concept — only arXiv submits.
                keyboardActions = KeyboardActions(onSearch = { if (tab == 1) viewModel.submit() }),
            )

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
                LibraryPane(
                    state = state,
                    selection = selection,
                    onPaperClick = onPaperClick,
                    onSave = saveOne,
                    taxonomy = browseState.groups,
                    expandedGroups = expandedGroups,
                    onCategoryClick = onCategoryClick,
                    onFollowToggle = browseViewModel::setFollowed,
                )
            } else {
                var showFilters by rememberSaveable { mutableStateOf(false) }
                ArxivFilterBar(
                    state = state,
                    onScope = viewModel::setScope,
                    onOpenFilters = { showFilters = true },
                )
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
                    else -> ResultList(state, selection, onPaperClick, saveOne, viewModel::loadMore)
                }

                if (showFilters) {
                    SearchFilterSheet(
                        state = state,
                        onToggleCategory = viewModel::toggleCategory,
                        onClearCategories = viewModel::clearCategories,
                        onDatePreset = viewModel::setDatePreset,
                        onSort = viewModel::setSort,
                        onApply = {
                            showFilters = false
                            viewModel.submit()
                        },
                        onDismiss = { showFilters = false },
                    )
                }
            }
        }
    }

    organizeIds?.let { ids ->
        dev.blokz.arxiver.feature.organize.OrganizeSheet(paperIds = ids, onDismiss = { organizeIds = null })
    }

    if (showSourceFollow) {
        SourceFollowSheet(onDismiss = { showSourceFollow = false })
    }

    if (showDispatch) {
        dev.blokz.arxiver.feature.claude.DispatchSheet(
            paperIds = selection.selected.toList(),
            onDismiss = {
                showDispatch = false
                selection.clear()
            },
            onGoToRoutines = {
                showDispatch = false
                onOpenRoutines()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArxivFilterBar(
    state: SearchUiState,
    onScope: (SearchFilter.Field) -> Unit,
    onOpenFilters: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchFilter.Field.entries.forEach { field ->
            FilterChip(
                selected = state.scope == field,
                onClick = { onScope(field) },
                label = { Text(stringResource(field.labelRes())) },
            )
        }
        AssistChip(
            onClick = onOpenFilters,
            leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.cd_search_filters)) },
            label = {
                Text(stringResource(R.string.search_filters) + if (state.filtersActive) " •" else "")
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SearchFilterSheet(
    state: SearchUiState,
    onToggleCategory: (String) -> Unit,
    onClearCategories: () -> Unit,
    onDatePreset: (DatePreset) -> Unit,
    onSort: (ArxivQuery.SortBy, ArxivQuery.SortOrder) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val grouped = remember { ArxivTaxonomy.categories.groupBy { it.group } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.search_filters_title), style = MaterialTheme.typography.titleLarge)

            // Date
            Text(stringResource(R.string.search_filter_date), style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                DatePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = state.datePreset == preset,
                        onClick = { onDatePreset(preset) },
                        label = { Text(stringResource(preset.labelRes())) },
                    )
                }
            }

            // Sort
            Text(stringResource(R.string.search_filter_sort), style = MaterialTheme.typography.labelLarge)
            val byDate = state.sortBy == ArxivQuery.SortBy.SUBMITTED_DATE
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SortChip(R.string.search_sort_relevance, state.sortBy == ArxivQuery.SortBy.RELEVANCE) {
                    onSort(ArxivQuery.SortBy.RELEVANCE, ArxivQuery.SortOrder.DESCENDING)
                }
                SortChip(
                    R.string.search_sort_newest,
                    byDate && state.sortOrder == ArxivQuery.SortOrder.DESCENDING,
                ) { onSort(ArxivQuery.SortBy.SUBMITTED_DATE, ArxivQuery.SortOrder.DESCENDING) }
                SortChip(
                    R.string.search_sort_oldest,
                    byDate && state.sortOrder == ArxivQuery.SortOrder.ASCENDING,
                ) { onSort(ArxivQuery.SortBy.SUBMITTED_DATE, ArxivQuery.SortOrder.ASCENDING) }
            }

            // Categories
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.search_filter_categories),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (state.categories.isNotEmpty()) {
                    TextButton(onClick = onClearCategories) { Text(stringResource(R.string.search_filter_clear)) }
                }
            }
            grouped.forEach { (group, cats) ->
                Text(
                    group,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    cats.forEach { cat ->
                        FilterChip(
                            selected = cat.code in state.categories,
                            onClick = { onToggleCategory(cat.code) },
                            label = { Text(cat.code) },
                        )
                    }
                }
            }

            Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.search_filter_apply))
            }
        }
    }
}

@Composable
private fun SortChip(
    labelRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(stringResource(labelRes)) })
}

private fun SearchFilter.Field.labelRes(): Int =
    when (this) {
        SearchFilter.Field.ALL -> R.string.search_scope_all
        SearchFilter.Field.TITLE -> R.string.search_scope_title
        SearchFilter.Field.AUTHOR -> R.string.search_scope_author
        SearchFilter.Field.ABSTRACT -> R.string.search_scope_abstract
    }

private fun DatePreset.labelRes(): Int =
    when (this) {
        DatePreset.ANY -> R.string.search_date_any
        DatePreset.PAST_WEEK -> R.string.search_date_week
        DatePreset.PAST_MONTH -> R.string.search_date_month
        DatePreset.PAST_YEAR -> R.string.search_date_year
    }

@Composable
private fun LibraryPane(
    state: SearchUiState,
    selection: dev.blokz.arxiver.ui.components.SelectionState,
    onPaperClick: (String) -> Unit,
    onSave: (String) -> Unit,
    taxonomy: List<CategoryGroup>,
    expandedGroups: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    onCategoryClick: (code: String, name: String) -> Unit,
    onFollowToggle: (ArxivCategory, Boolean) -> Unit,
) {
    when {
        // Blank query = the resting state: the category taxonomy (the old Browse tab), now the
        // app's category directory, reached by clearing the query on the Library scope (PC.2).
        state.query.isBlank() ->
            TaxonomyList(
                groups = taxonomy,
                expandedGroups = expandedGroups,
                onCategoryClick = onCategoryClick,
                onFollowToggle = onFollowToggle,
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
                itemsIndexed(state.localResults, key = { _, hit -> hit.paper.ref.storageId }) { index, hit ->
                    val id = hit.paper.ref.storageId
                    SwipeablePaperRow(
                        paper = hit.paper,
                        onClick = { if (selection.isActive) selection.toggle(id) else onPaperClick(id) },
                        onLongClick = { selection.toggle(id) },
                        onSwipeSave = { onSave(id) },
                        selectionMode = selection.isActive,
                        selected = selection.contains(id),
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
    selection: dev.blokz.arxiver.ui.components.SelectionState,
    onPaperClick: (String) -> Unit,
    onSave: (String) -> Unit,
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
        itemsIndexed(state.results, key = { _, paper -> paper.ref.storageId }) { index, paper ->
            val id = paper.ref.storageId
            SwipeablePaperRow(
                paper = paper,
                onClick = { if (selection.isActive) selection.toggle(id) else onPaperClick(id) },
                onLongClick = { selection.toggle(id) },
                onSwipeSave = { onSave(id) },
                selectionMode = selection.isActive,
                selected = selection.contains(id),
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

/** The category taxonomy (moved verbatim from the old BrowseScreen) — the Explore resting state. */
@Composable
private fun TaxonomyList(
    groups: List<CategoryGroup>,
    expandedGroups: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    onCategoryClick: (code: String, name: String) -> Unit,
    onFollowToggle: (ArxivCategory, Boolean) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(groups, key = { "group-${it.name}" }) { group ->
            val expanded = expandedGroups[group.name] == true
            // Header + categories live in one item so the group expands as a unit.
            Column {
                GroupHeader(
                    group = group,
                    expanded = expanded,
                    onToggle = { expandedGroups[group.name] = !expanded },
                )
                AnimatedVisibility(
                    visible = expanded,
                    enter =
                        expandVertically(tween(ArxiverMotion.DURATION_MEDIUM)) +
                            fadeIn(tween(ArxiverMotion.DURATION_MEDIUM)),
                    exit =
                        shrinkVertically(tween(ArxiverMotion.DURATION_MEDIUM)) +
                            fadeOut(tween(ArxiverMotion.DURATION_SHORT)),
                ) {
                    Column {
                        group.categories.forEach { item ->
                            CategoryRow(
                                item = item,
                                onClick = { onCategoryClick(item.category.code, item.category.name) },
                                onFollowToggle = { onFollowToggle(item.category, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(
    group: CategoryGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(ArxiverMotion.DURATION_MEDIUM),
        label = "chevron",
    )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = 2.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(
                    if (expanded) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
                )
                .clickable(onClick = onToggle)
                .padding(horizontal = Spacing.sm, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        val followedCount = group.categories.count { it.followed }
        if (followedCount > 0) {
            StatusChip(
                text = pluralStringResource(R.plurals.browse_following_count, followedCount, followedCount),
                modifier = Modifier.padding(end = Spacing.sm),
            )
        }
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription =
                stringResource(
                    if (expanded) R.string.cd_collapse_group else R.string.cd_expand_group,
                ),
            modifier = Modifier.rotate(chevronRotation),
        )
    }
}

@Composable
private fun CategoryRow(
    item: CategoryWithFollowState,
    onClick: () -> Unit,
    onFollowToggle: (Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(start = Spacing.xl, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.category.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                item.category.code,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val followDescription = stringResource(R.string.cd_follow_category, item.category.name)
        Switch(
            checked = item.followed,
            onCheckedChange = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onFollowToggle(it)
            },
            modifier = Modifier.semantics { contentDescription = followDescription },
        )
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LocalResultsPreview() {
    ArxiverTheme {
        LibraryPane(
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
            selection = dev.blokz.arxiver.ui.components.SelectionState(),
            onPaperClick = {},
            onSave = {},
            taxonomy = emptyList(),
            expandedGroups = remember { androidx.compose.runtime.mutableStateMapOf() },
            onCategoryClick = { _, _ -> },
            onFollowToggle = { _, _ -> },
        )
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ExploreTaxonomyRestingPreview() {
    val fixture =
        listOf(
            CategoryGroup(
                name = "Computer Science",
                categories =
                    listOf(
                        CategoryWithFollowState(
                            ArxivCategory("cs.LG", "Machine Learning", "Computer Science"),
                            followed = true,
                        ),
                        CategoryWithFollowState(
                            ArxivCategory("cs.CL", "Computation and Language", "Computer Science"),
                            followed = false,
                        ),
                    ),
            ),
        )
    ArxiverTheme {
        LibraryPane(
            state = SearchUiState(query = ""),
            selection = dev.blokz.arxiver.ui.components.SelectionState(),
            onPaperClick = {},
            onSave = {},
            taxonomy = fixture,
            expandedGroups = remember { androidx.compose.runtime.mutableStateMapOf("Computer Science" to true) },
            onCategoryClick = { _, _ -> },
            onFollowToggle = { _, _ -> },
        )
    }
}
