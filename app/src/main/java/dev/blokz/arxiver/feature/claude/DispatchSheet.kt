package dev.blokz.arxiver.feature.claude

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.claude.RoutineAction

/**
 * SPEC-CLAUDE-BRIDGE §5 confirm sheet: routine → action → instruction →
 * notes toggle → payload preview → send. Every dispatch flows through here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DispatchSheet(
    paperIds: List<String>,
    onDismiss: () -> Unit,
    onGoToRoutines: () -> Unit,
    presetAction: RoutineAction? = null,
    viewModel: DispatchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(paperIds) { viewModel.start(paperIds, presetAction) }
    LaunchedEffect(state.completed) {
        if (state.completed != null) onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = pluralStringResource(R.plurals.dispatch_title, paperIds.size, paperIds.size),
                style = MaterialTheme.typography.titleLarge,
            )

            if (state.routines.isEmpty()) {
                Text(
                    text = stringResource(R.string.dispatch_no_routines),
                    style = MaterialTheme.typography.bodyMedium,
                )
                FilledTonalButton(onClick = onGoToRoutines) {
                    Text(stringResource(R.string.dispatch_setup_routines))
                }
                return@Column
            }

            // Routine picker
            Text(stringResource(R.string.dispatch_routine_label), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.routines.forEach { routine ->
                    FilterChip(
                        selected = state.selectedRoutineId == routine.id,
                        onClick = { viewModel.selectRoutine(routine.id) },
                        label = { Text(routine.name) },
                    )
                }
            }

            // Action picker (contextual to selection size)
            Text(stringResource(R.string.dispatch_action_label), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.availableActions.forEach { action ->
                    FilterChip(
                        selected = state.action == action,
                        onClick = { viewModel.selectAction(action) },
                        label = { Text(action.label()) },
                    )
                }
            }

            OutlinedTextField(
                value = state.instruction,
                onValueChange = viewModel::setInstruction,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.dispatch_instruction_label)) },
                minLines = 2,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.dispatch_include_notes),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.dispatch_include_notes_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.includeNotes, onCheckedChange = viewModel::setIncludeNotes)
            }

            TextButton(onClick = viewModel::togglePreview) {
                Text(
                    stringResource(
                        if (state.previewExpanded) R.string.dispatch_hide_preview else R.string.dispatch_show_preview,
                    ),
                )
            }
            if (state.previewExpanded) {
                Text(
                    text = state.previewJson ?: stringResource(R.string.dispatch_preview_loading),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                )
            }

            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            FilledTonalButton(
                onClick = viewModel::send,
                enabled = !state.sending && state.selectedRoutineId != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(if (state.sending) R.string.dispatch_sending else R.string.dispatch_send),
                )
            }
        }
    }
}

@Composable
private fun RoutineAction.label(): String =
    when (this) {
        RoutineAction.DIGEST -> stringResource(R.string.action_digest)
        RoutineAction.DEEP_DIVE -> stringResource(R.string.action_deep_dive)
        RoutineAction.COMPARE -> stringResource(R.string.action_compare)
        RoutineAction.WEEKLY_REVIEW -> stringResource(R.string.action_weekly_review)
        RoutineAction.LITERATURE_SCAN -> stringResource(R.string.action_literature_scan)
        RoutineAction.CUSTOM -> stringResource(R.string.action_custom)
        RoutineAction.PING -> "ping"
    }
