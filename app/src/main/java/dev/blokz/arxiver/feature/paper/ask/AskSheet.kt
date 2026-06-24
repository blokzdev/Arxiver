package dev.blokz.arxiver.feature.paper.ask

import android.content.res.Configuration
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.chat.ChatPreview
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.core.search.RetrievalScope
import dev.blokz.arxiver.data.Citation
import dev.blokz.arxiver.ui.markdown.MarkdownText
import dev.blokz.arxiver.ui.markdown.RichBlockWebView
import dev.blokz.arxiver.ui.markdown.RichContent
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
    scope: RetrievalScope,
    onDismiss: () -> Unit,
    onConfigureProvider: () -> Unit,
    sessionId: Long? = null,
    viewModel: AskViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(scope, sessionId) { viewModel.start(scope, sessionId) }

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

        if (state.indexing) {
            Text(
                stringResource(R.string.ask_indexing_collection),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
    var sourcesExpanded by remember { mutableStateOf(false) }
    Surface(
        color = bubbleColor,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            val body = message.text.ifEmpty { if (message.streaming) stringResource(R.string.ask_thinking) else "" }
            if (isUser || message.error) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            } else {
                // Assistant answers render as markdown (P-Rich R0); a tapped [n] opens Sources.
                val onCite: ((Int) -> Unit)? =
                    if (message.citations.isNotEmpty()) {
                        { sourcesExpanded = true }
                    } else {
                        null
                    }
                // A math answer renders via the sandboxed offline KaTeX WebView (P-Rich R1)
                // once the stream settles; everything else uses the native markdown renderer.
                if (!message.streaming && RichContent.has(body)) {
                    RichBlockWebView(markdown = body, onCitationClick = onCite)
                } else {
                    MarkdownText(markdown = body, color = MaterialTheme.colorScheme.onSurface, onCitationClick = onCite)
                }
                if (message.citations.isNotEmpty() && !message.streaming) {
                    CitationSources(
                        citations = message.citations,
                        expanded = sourcesExpanded,
                        onToggle = { sourcesExpanded = !sourcesExpanded },
                    )
                }
            }
            if (message.streaming) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/** Collapsible list of the sources an answer cited as `[n]` (P-Rich R0). */
@Composable
private fun CitationSources(
    citations: List<Citation>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = Spacing.xs)) {
        Row(
            modifier = Modifier.clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.ask_sources, citations.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = Spacing.sm, top = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                citations.forEach { citation ->
                    val snippet = citation.excerpt.trim()
                    Text(
                        text =
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("[${citation.index}] ") }
                                append("arXiv:${citation.paperId} — ")
                                append(if (snippet.length > 160) snippet.take(160).trimEnd() + "…" else snippet)
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                            AskMessage(
                                AskRole.ASSISTANT,
                                "The paper proposes a **three-component** pipeline [1]:\n\n" +
                                    "1. A cold-start data stage\n" +
                                    "2. RL dataset curation\n" +
                                    "3. An adaptive tool-invocation strategy\n\n" +
                                    "| Stage | Role |\n|---|---|\n| Cold-start | bootstrap |\n| RL | curation |",
                                citations =
                                    listOf(
                                        Citation(
                                            1,
                                            "2606.23678",
                                            "We propose a comprehensive three-component solution…",
                                        ),
                                    ),
                            ),
                        ),
                ),
            onInput = {}, onSend = {}, onSummarize = {}, onSetIncludeNotes = {},
            onConfirmSend = {}, onCancelConfirm = {}, onStop = {}, onConfigureProvider = {},
        )
    }
}
