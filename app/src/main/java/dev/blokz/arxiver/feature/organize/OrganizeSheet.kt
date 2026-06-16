package dev.blokz.arxiver.feature.organize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.ui.components.SectionHeader
import dev.blokz.arxiver.ui.theme.Spacing

/**
 * Bulk "Add to collection / tag" sheet (UX2.4/2.5). Files the given paper(s) into existing
 * collections and tags — tri-state for multi-select — or a freshly created one. Reused for a single
 * paper (post-save feedback action / detail) and for a multi-select set.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OrganizeSheet(
    paperIds: List<String>,
    onDismiss: () -> Unit,
    viewModel: OrganizeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(paperIds) { viewModel.start(paperIds) }

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
            Text(
                text = pluralStringResource(R.plurals.organize_title, paperIds.size, paperIds.size),
                style = MaterialTheme.typography.titleLarge,
            )

            SectionHeader(stringResource(R.string.paper_collections_heading))
            var newCollection by remember { mutableStateOf<String?>(null) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                state.collections.forEach { target ->
                    OrganizeChip(
                        label = target.name,
                        state = target.state,
                        onClick = {
                            if (target.state == MembershipState.ALL) {
                                viewModel.removeCollection(target.id)
                            } else {
                                viewModel.addCollection(target.id)
                            }
                        },
                    )
                }
                AssistChip(
                    onClick = { newCollection = "" },
                    label = { Text(stringResource(R.string.paper_new_collection)) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                )
            }
            newCollection?.let { value ->
                InlineCreateRow(
                    value = value,
                    hint = stringResource(R.string.library_collection_name_hint),
                    onValueChange = { newCollection = it },
                    onConfirm = {
                        viewModel.createCollectionWithSelection(value)
                        newCollection = null
                    },
                )
            }

            SectionHeader(stringResource(R.string.paper_tags_heading))
            var newTag by remember { mutableStateOf<String?>(null) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                state.tags.forEach { target ->
                    OrganizeChip(
                        label = "#${target.name}",
                        state = target.state,
                        onClick = { viewModel.addExistingTag(target) },
                    )
                }
                AssistChip(
                    onClick = { newTag = "" },
                    label = { Text(stringResource(R.string.organize_new_tag)) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                )
            }
            newTag?.let { value ->
                InlineCreateRow(
                    value = value,
                    hint = stringResource(R.string.paper_tag_hint),
                    onValueChange = { newTag = it },
                    onConfirm = {
                        viewModel.addTag(value)
                        newTag = null
                    },
                )
            }

            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.organize_done))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrganizeChip(
    label: String,
    state: MembershipState,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = state != MembershipState.NONE,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon =
            when (state) {
                MembershipState.ALL -> {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                }
                MembershipState.SOME -> {
                    { Icon(Icons.Filled.Remove, contentDescription = null) }
                }
                MembershipState.NONE -> null
            },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InlineCreateRow(
    value: String,
    hint: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier.weight(1f),
            placeholder = { Text(hint) },
        )
        TextButton(onClick = onConfirm, enabled = value.isNotBlank()) {
            Text(stringResource(R.string.action_create))
        }
    }
}
