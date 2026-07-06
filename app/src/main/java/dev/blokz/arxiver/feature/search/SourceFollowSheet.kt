package dev.blokz.arxiver.feature.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.network.PreprintSourceRegistry
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing

/**
 * The source + category follow picker (P-Feeds PF.3). Pick a non-arXiv source, then follow whole-source or a
 * specific category into the Today inbox. Vocabulary is the static [PreprintSourceRegistry] — opening the sheet
 * issues ZERO network requests (the OpenAlex metering red line). Toggle state keys on `(origin, value)`, so the
 * same category token followed on one source never lights up another.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceFollowSheet(
    onDismiss: () -> Unit,
    viewModel: SourceFollowViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SourceFollowContent(
            state = state,
            onToggle = { source, value, label, followed ->
                viewModel.setFollowed(source, value, label, followed)
            },
        )
    }
}

/** Stateless picker body (previewable): source chips → the selected source's whole-source + category toggles. */
@Composable
private fun SourceFollowContent(
    state: SourceFollowUiState,
    onToggle: (source: Source, value: String, label: String, followed: Boolean) -> Unit,
) {
    var selected by rememberSaveable {
        mutableStateOf(state.sources.firstOrNull()?.source ?: Source.BIORXIV)
    }
    val info = state.sources.firstOrNull { it.source == selected } ?: return

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
    ) {
        Text(
            stringResource(R.string.follow_sources_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.follow_sources_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.md),
        )

        // Source selector — the pickable non-arXiv sources.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            state.sources.forEach { s ->
                FilterChip(
                    selected = s.source == selected,
                    onClick = { selected = s.source },
                    label = { Text(s.source.displayName) },
                )
            }
        }

        // New-source PDFs are host-gated → they open in the browser (honest, not overclaimed).
        Text(
            stringResource(R.string.follow_sources_external_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs),
        )

        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
            if (info.allowsWholeSource) {
                item(key = "whole") {
                    FollowToggleRow(
                        label = stringResource(R.string.follow_sources_whole, selected.displayName),
                        checked = state.isFollowed(selected, PreprintSourceRegistry.WHOLE_SOURCE_VALUE),
                        onCheckedChange = {
                            onToggle(selected, PreprintSourceRegistry.WHOLE_SOURCE_VALUE, selected.displayName, it)
                        },
                    )
                }
            }
            items(info.categories, key = { "cat_${it.value}" }) { opt ->
                FollowToggleRow(
                    label = opt.label,
                    checked = state.isFollowed(selected, opt.value),
                    onCheckedChange = { onToggle(selected, opt.value, opt.label, it) },
                )
            }
        }
    }
}

@Composable
private fun FollowToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val cd = stringResource(R.string.cd_follow_category, label)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onCheckedChange(it)
            },
            modifier = Modifier.semantics { contentDescription = cd },
        )
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SourceFollowContentPreview() {
    // bioRxiv selected, "neuroscience" already followed — exercises the whole-source row + a checked toggle.
    val followed = setOf(SourceFollowUiState.key(Source.BIORXIV.wire, "neuroscience"))
    ArxiverTheme {
        SourceFollowContent(
            state = SourceFollowUiState(followedKeys = followed),
            onToggle = { _, _, _, _ -> },
        )
    }
}
