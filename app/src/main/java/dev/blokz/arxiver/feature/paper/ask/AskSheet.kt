package dev.blokz.arxiver.feature.paper.ask

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.chat.ChatPreview
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing

/**
 * Per-paper "Ask" sheet (P2.3): streams a grounded answer, gates cloud calls
 * behind the "what leaves the device" confirm, offers a one-tap summarize, and
 * surfaces the provider/on-device indicator + not-configured/error states.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskSheet(
    paperId: String,
    onDismiss: () -> Unit,
    onConfigureProvider: () -> Unit,
    viewModel: AskViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(paperId) { viewModel.start(paperId) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        AskSheetContent(
            state = state,
            onInput = viewModel::setInput,
            onSend = viewModel::send,
            onSummarize = viewModel::summarize,
            onSetIncludeNotes = viewModel::setIncludeNotes,
            onConfirmSend = viewModel::confirmSend,
            onCancelConfirm = viewModel::cancelConfirm,
            onStop = viewModel::cancel,
            onConfigureProvider = onConfigureProvider,
        )
    }
}

@Composable
private fun AskSheetContent(
    state: AskUiState,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onSummarize: () -> Unit,
    onSetIncludeNotes: (Boolean) -> Unit,
    onConfirmSend: () -> Unit,
    onCancelConfirm: () -> Unit,
    onStop: () -> Unit,
    onConfigureProvider: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(stringResource(R.string.ask_title), style = MaterialTheme.typography.titleLarge)

        state.provider?.let { ProviderIndicator(it, state.isCloud) }

        if (state.messages.isEmpty() && !state.preparing) {
            Text(
                stringResource(R.string.ask_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AssistChip(onClick = onSummarize, label = { Text(stringResource(R.string.ask_summarize)) })
        } else {
            state.messages.forEach { AskBubble(it) }
        }

        if (state.preparing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (state.notConfigured) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(stringResource(R.string.ask_not_configured), style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = onConfigureProvider) {
                        Text(stringResource(R.string.ask_configure_provider))
                    }
                }
            }
        }

        state.error?.let {
            Text(
                stringResource(it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val confirm = state.pendingConfirm
        if (confirm != null) {
            ConfirmCard(preview = confirm, onSend = onConfirmSend, onCancel = onCancelConfirm)
        } else {
            IncludeNotesRow(state.includeNotes, onSetIncludeNotes)
            InputRow(
                input = state.input,
                streaming = state.streaming,
                preparing = state.preparing,
                onInput = onInput,
                onSend = onSend,
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun ProviderIndicator(
    provider: ProviderId,
    isCloud: Boolean,
) {
    val text =
        if (isCloud) {
            stringResource(R.string.ask_provider_cloud, providerLabel(provider))
        } else {
            stringResource(R.string.ask_provider_ondevice)
        }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AskBubble(message: AskMessage) {
    val isUser = message.role == AskRole.USER
    val bubbleColor =
        if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(
        color = bubbleColor,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            val body = message.text.ifEmpty { if (message.streaming) stringResource(R.string.ask_thinking) else "" }
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (message.streaming) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun ConfirmCard(
    preview: ChatPreview,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.ask_confirm_title), style = MaterialTheme.typography.titleSmall)
            Text(
                preview.text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ask_confirm_cancel))
                }
                Button(onClick = onSend, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ask_confirm_send))
                }
            }
        }
    }
}

@Composable
private fun IncludeNotesRow(
    includeNotes: Boolean,
    onSetIncludeNotes: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.ask_include_notes), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.ask_include_notes_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = includeNotes, onCheckedChange = onSetIncludeNotes)
    }
}

@Composable
private fun InputRow(
    input: String,
    streaming: Boolean,
    preparing: Boolean,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInput,
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.ask_input_hint)) },
            enabled = !streaming,
            minLines = 1,
        )
        if (streaming) {
            FilledTonalButton(onClick = onStop) { Text(stringResource(R.string.ask_stop)) }
        } else {
            Button(onClick = onSend, enabled = input.isNotBlank() && !preparing) {
                Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.ask_send))
            }
        }
    }
}

@Composable
private fun providerLabel(provider: ProviderId): String =
    stringResource(
        when (provider) {
            ProviderId.CLAUDE -> R.string.ai_provider_claude
            ProviderId.GEMINI -> R.string.ai_provider_gemini
            ProviderId.ON_DEVICE -> R.string.ai_provider_on_device
        },
    )

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AskSheetEmptyPreview() {
    ArxiverTheme {
        AskSheetContent(
            state = AskUiState(provider = ProviderId.ON_DEVICE, isCloud = false),
            onInput = {}, onSend = {}, onSummarize = {}, onSetIncludeNotes = {},
            onConfirmSend = {}, onCancelConfirm = {}, onStop = {}, onConfigureProvider = {},
        )
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AskSheetConversationPreview() {
    ArxiverTheme {
        AskSheetContent(
            state =
                AskUiState(
                    provider = ProviderId.CLAUDE,
                    isCloud = true,
                    messages =
                        listOf(
                            AskMessage(AskRole.USER, "What is the main contribution?"),
                            AskMessage(AskRole.ASSISTANT, "The paper introduces…", streaming = true),
                        ),
                ),
            onInput = {}, onSend = {}, onSummarize = {}, onSetIncludeNotes = {},
            onConfirmSend = {}, onCancelConfirm = {}, onStop = {}, onConfigureProvider = {},
        )
    }
}
