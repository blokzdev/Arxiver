package dev.blokz.arxiver.feature.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.data.CategoryWithFollowState
import dev.blokz.arxiver.ui.components.StatusChip
import dev.blokz.arxiver.ui.theme.ArxiverMotion
import dev.blokz.arxiver.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onCategoryClick: (code: String, name: String) -> Unit,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val expandedGroups = remember { mutableStateMapOf("Computer Science" to true) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_browse)) }) },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            items(state.groups, key = { "group-${it.name}" }) { group ->
                val expanded = expandedGroups[group.name] == true
                // Header + categories live in one item so the group expands
                // as a unit (groups are small enough to compose eagerly).
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
                                    onFollowToggle = { viewModel.setFollowed(item.category, it) },
                                )
                            }
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
private fun BrowseGroupPreview() {
    dev.blokz.arxiver.ui.theme.ArxiverTheme {
        Column {
            GroupHeader(
                group =
                    CategoryGroup(
                        name = "Computer Science",
                        categories =
                            listOf(
                                CategoryWithFollowState(
                                    dev.blokz.arxiver.core.model.ArxivCategory(
                                        code = "cs.LG",
                                        name = "Machine Learning",
                                        group = "Computer Science",
                                    ),
                                    followed = true,
                                ),
                            ),
                    ),
                expanded = true,
                onToggle = {},
            )
            CategoryRow(
                item =
                    CategoryWithFollowState(
                        dev.blokz.arxiver.core.model.ArxivCategory(
                            code = "cs.LG",
                            name = "Machine Learning",
                            group = "Computer Science",
                        ),
                        followed = true,
                    ),
                onClick = {},
                onFollowToggle = {},
            )
        }
    }
}
