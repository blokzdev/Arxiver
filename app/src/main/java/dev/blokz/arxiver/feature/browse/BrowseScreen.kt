package dev.blokz.arxiver.feature.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.data.CategoryWithFollowState

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
            state.groups.forEach { group ->
                val expanded = expandedGroups[group.name] == true
                item(key = "group-${group.name}") {
                    GroupHeader(
                        group = group,
                        expanded = expanded,
                        onToggle = { expandedGroups[group.name] = !expanded },
                    )
                }
                if (expanded) {
                    items(group.categories, key = { it.category.code }) { item ->
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

@Composable
private fun GroupHeader(
    group: CategoryGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        val followedCount = group.categories.count { it.followed }
        if (followedCount > 0) {
            Text(
                text = pluralStringResource(R.plurals.browse_following_count, followedCount, followedCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription =
                stringResource(
                    if (expanded) R.string.cd_collapse_group else R.string.cd_expand_group,
                ),
        )
    }
}

@Composable
private fun CategoryRow(
    item: CategoryWithFollowState,
    onClick: () -> Unit,
    onFollowToggle: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 24.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
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
            onCheckedChange = onFollowToggle,
            modifier = Modifier.semantics { contentDescription = followDescription },
        )
    }
}
