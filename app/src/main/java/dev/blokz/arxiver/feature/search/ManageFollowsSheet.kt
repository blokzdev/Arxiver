package dev.blokz.arxiver.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.database.entity.FollowEntity
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing

/**
 * The "Following" management sheet (P-FeedPolish PFP.2) — sibling of [SourceFollowSheet], opened from the Explore
 * top bar. Lists every follow (all sources AND all types: category / whole-source / author / query) grouped by
 * source, each removable. Reads pure DB via the ViewModel → opening it issues ZERO network requests. The empty
 * state routes to [SourceFollowSheet] via [onAddFollows] so "nothing followed" has a way forward.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageFollowsSheet(
    onDismiss: () -> Unit,
    onAddFollows: () -> Unit,
    viewModel: ManageFollowsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ManageFollowsContent(
            state = state,
            onUnfollow = viewModel::unfollow,
            onAddFollows = onAddFollows,
        )
    }
}

/** Stateless body (previewable): a header, then loading / empty / grouped-list per [ManageFollowsUiState]. */
@Composable
private fun ManageFollowsContent(
    state: ManageFollowsUiState,
    onUnfollow: (FollowEntity) -> Unit,
    onAddFollows: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
    ) {
        Text(
            stringResource(R.string.manage_follows_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = Spacing.md),
        )

        when {
            state.loading ->
                Box(modifier = Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            state.isEmpty -> EmptyFollows(onAddFollows)
            else ->
                LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    state.groups.forEach { group ->
                        item(key = "hdr_${group.source.wire}") {
                            Text(
                                group.source.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs),
                            )
                        }
                        items(group.rows, key = { it.entity.id }) { row ->
                            FollowManageRow(
                                row = row,
                                sourceDisplayName = group.source.displayName,
                                onUnfollow = { onUnfollow(row.entity) },
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun EmptyFollows(onAddFollows: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            stringResource(R.string.manage_follows_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onAddFollows) {
            Text(stringResource(R.string.manage_follows_empty_cta))
        }
    }
}

@Composable
private fun FollowManageRow(
    row: FollowRowUi,
    sourceDisplayName: String,
    onUnfollow: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val primary =
        if (row.isWholeSource) {
            stringResource(R.string.follow_sources_whole, sourceDisplayName)
        } else {
            row.label
        }
    val secondary =
        when (row.type) {
            FollowEntity.TYPE_AUTHOR -> stringResource(R.string.follow_type_author)
            FollowEntity.TYPE_QUERY -> stringResource(R.string.follow_type_query)
            else -> null
        }
    val cd = stringResource(R.string.cd_unfollow, primary)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(primary, style = MaterialTheme.typography.bodyLarge)
            if (secondary != null) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Soft health hint (PFP.3): a follow whose last EMPTY_STREAK_WARN syncs delivered nothing. Muted
            // onSurfaceVariant (not error) — an empty feed is a Success, not a failure; the copy stays gentle.
            if (row.isQuiet) {
                Text(
                    stringResource(R.string.follow_health_quiet),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onUnfollow()
            },
            modifier = Modifier.semantics { contentDescription = cd },
        ) {
            Icon(Icons.Filled.Close, contentDescription = null)
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ManageFollowsContentPreview() {
    // chemRxiv whole-source + a category (with the quiet-feed hint) + an arXiv author follow — exercises grouping,
    // "All of X", type labels, and the PFP.3 health subtitle.
    val follows =
        listOf(
            FollowEntity(id = 1, type = "category", value = "", label = "chemRxiv", createdAt = 0, origin = "chemrxiv"),
            FollowEntity(
                id = 2,
                type = "category",
                value = "fields/16",
                label = "Computer Science",
                createdAt = 0,
                origin = "chemrxiv",
                emptySyncStreak = 4,
            ),
            FollowEntity(id = 3, type = "author", value = "Yann LeCun", label = "Yann LeCun", createdAt = 0),
            FollowEntity(id = 4, type = "query", value = "diffusion models", label = "diffusion models", createdAt = 0),
        )
    ArxiverTheme {
        ManageFollowsContent(
            state = ManageFollowsUiState(groups = ManageFollowsViewModel.groupBySource(follows), loading = false),
            onUnfollow = {},
            onAddFollows = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ManageFollowsEmptyPreview() {
    ArxiverTheme {
        ManageFollowsContent(
            state = ManageFollowsUiState(groups = emptyList(), loading = false),
            onUnfollow = {},
            onAddFollows = {},
        )
    }
}
