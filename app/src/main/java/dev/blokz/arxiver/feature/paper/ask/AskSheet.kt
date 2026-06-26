package dev.blokz.arxiver.feature.paper.ask

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.chat.ChatMode
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
    /** Open an arXiv cross-reference tapped in an answer (P-Rich R3a); null disables cross-refs. */
    onOpenCrossRef: ((String) -> Unit)? = null,
    /** Pin an assistant answer into the paper's notes (P-Rich R3a); null hides the action
     *  (e.g. collection-scope chat, which has no single target paper). */
    onPinAnswer: ((String) -> Unit)? = null,
    /** Share a single answer as Markdown via the OS share sheet (P-Rich R4); null hides it. */
    onShareAnswer: ((AskMessage) -> Unit)? = null,
    /** Share the whole conversation as Markdown via the OS share sheet (P-Rich R4); null hides it. */
    onShareConversation: ((List<AskMessage>) -> Unit)? = null,
    viewModel: AskViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(scope, sessionId) { viewModel.start(scope, sessionId) }
    // The "what you can ask" presets, filtered to this scope (paper drops collection-only and
    // vice versa) — P-Rich R3c. The vision preset (R3d.4) is additionally gated on
    // state.visionAvailable, which the VM resolves after open — so re-key on it.
    val presets =
        remember(scope, state.visionAvailable) {
            AskPresets.forScope(scope is RetrievalScope.Paper, visionAvailable = state.visionAvailable)
        }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        AskSheetContent(
            state = state,
            presets = presets,
            onInput = viewModel::setInput,
            onSend = viewModel::send,
            onRunPreset = viewModel::runPreset,
            onRunVisionPreset = viewModel::runVisionPreset,
            onRunGraphArtifact = viewModel::runGraphArtifact,
            onFollowUp = viewModel::runPreset,
            onSetMode = viewModel::setMode,
            onSetIncludeNotes = viewModel::setIncludeNotes,
            onConfirmSend = viewModel::confirmSend,
            onCancelConfirm = viewModel::cancelConfirm,
            onStop = viewModel::cancel,
            onConfigureProvider = onConfigureProvider,
            onOpenCrossRef = onOpenCrossRef,
            onPinAnswer = onPinAnswer,
            onShareAnswer = onShareAnswer,
            onShareConversation = onShareConversation,
        )
    }
}

@Composable
private fun AskSheetContent(
    state: AskUiState,
    presets: List<AskPreset>,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onRunPreset: (String) -> Unit,
    /** R3d.4: run the vision preset for a chosen 1-based page (instruction, pageNumber). */
    onRunVisionPreset: (String, Int) -> Unit,
    /** P-Atlas PA.1: run an app-composed artifact preset (e.g. the relation graph); arg = the user-turn label. */
    onRunGraphArtifact: (String) -> Unit,
    onFollowUp: (String) -> Unit,
    onSetMode: (ChatMode) -> Unit,
    onSetIncludeNotes: (Boolean) -> Unit,
    onConfirmSend: () -> Unit,
    onCancelConfirm: () -> Unit,
    onStop: () -> Unit,
    onConfigureProvider: () -> Unit,
    onOpenCrossRef: ((String) -> Unit)? = null,
    onPinAnswer: ((String) -> Unit)? = null,
    onShareAnswer: ((AskMessage) -> Unit)? = null,
    onShareConversation: ((List<AskMessage>) -> Unit)? = null,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.ask_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            // Share the whole conversation as Markdown (P-Rich R4) — shown once a real answer exists.
            val canShareConversation =
                onShareConversation != null &&
                    state.messages.any {
                        it.role == AskRole.ASSISTANT && !it.streaming && !it.error && it.text.isNotBlank()
                    }
            if (canShareConversation) {
                IconButton(onClick = { onShareConversation!!(state.messages) }) {
                    Icon(Icons.Filled.Share, stringResource(R.string.cd_ask_share_conversation))
                }
            }
        }

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
        } else {
            // Follow-up chips render only on the latest assistant turn (a "next question" affordance,
            // not a wall of chips up the scrollback), and only when no turn is in flight.
            val lastAssistant = state.messages.indexOfLast { it.role == AskRole.ASSISTANT }
            val chipsEnabled = !state.streaming && !state.preparing
            state.messages.forEachIndexed { index, message ->
                AskBubble(
                    message = message,
                    onOpenCrossRef = onOpenCrossRef,
                    onPinAnswer = onPinAnswer,
                    onShareAnswer = onShareAnswer,
                    onFollowUp = if (index == lastAssistant) onFollowUp else null,
                    followUpsEnabled = chipsEnabled,
                )
            }
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
            // The vision preset (R3d.4) opens a page picker first; text presets run immediately.
            var pendingVision by remember { mutableStateOf<AskPreset?>(null) }
            PresetRow(
                presets = presets,
                enabled = !state.streaming && !state.preparing,
                pageCount = state.pageCount,
                onPresetClick = { preset ->
                    when {
                        preset.requiresVision -> pendingVision = preset
                        preset.artifact -> onRunGraphArtifact(preset.instruction)
                        else -> onRunPreset(preset.instruction)
                    }
                },
            )
            pendingVision?.let { preset ->
                PagePickDialog(
                    pageCount = state.pageCount,
                    onConfirm = { page ->
                        onRunVisionPreset(preset.instruction, page)
                        pendingVision = null
                    },
                    onDismiss = { pendingVision = null },
                )
            }
            ModeRow(mode = state.mode, enabled = !state.streaming && !state.preparing, onSetMode = onSetMode)
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
private fun AskBubble(
    message: AskMessage,
    onOpenCrossRef: ((String) -> Unit)? = null,
    onPinAnswer: ((String) -> Unit)? = null,
    onShareAnswer: ((AskMessage) -> Unit)? = null,
    onFollowUp: ((String) -> Unit)? = null,
    followUpsEnabled: Boolean = true,
) {
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
                // A math/diagram answer renders via the sandboxed offline WebView (P-Rich R1/R2)
                // once the stream settles; everything else uses the native markdown renderer.
                // Both paths linkify `arXiv:<id>` cross-refs to onOpenCrossRef (P-Rich R3a).
                if (!message.streaming && RichContent.has(body)) {
                    RichBlockWebView(markdown = body, onCitationClick = onCite, onArxivPaperClick = onOpenCrossRef)
                } else {
                    MarkdownText(
                        markdown = body,
                        color = MaterialTheme.colorScheme.onSurface,
                        onCitationClick = onCite,
                        onArxivCrossRefClick = onOpenCrossRef,
                    )
                }
                if (message.citations.isNotEmpty() && !message.streaming) {
                    CitationSources(
                        citations = message.citations,
                        expanded = sourcesExpanded,
                        onToggle = { sourcesExpanded = !sourcesExpanded },
                    )
                }
                // Action row under a settled, non-empty answer: pin-to-notes (P-Rich R3a, paper
                // scope only — onPinAnswer null in collection chat) + share-as-Markdown (P-Rich R4).
                // Pin's confirmation is shown in-sheet (the app snackbar sits behind the modal) by
                // flipping the button to a "done" state; share's confirmation is the OS chooser.
                val canAct = !message.streaming && !message.error && message.text.isNotBlank()
                if (canAct && (onPinAnswer != null || onShareAnswer != null)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        if (onPinAnswer != null) {
                            var pinned by remember(message.text) { mutableStateOf(false) }
                            TextButton(
                                onClick = {
                                    if (!pinned) {
                                        onPinAnswer(message.text)
                                        pinned = true
                                    }
                                },
                                enabled = !pinned,
                                contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp),
                            ) {
                                Icon(
                                    if (pinned) Icons.Filled.Check else Icons.Outlined.PushPin,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    stringResource(
                                        if (pinned) R.string.ask_pinned_to_notes else R.string.ask_pin_to_notes,
                                    ),
                                    modifier = Modifier.padding(start = Spacing.xs),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        if (onShareAnswer != null) {
                            TextButton(
                                onClick = { onShareAnswer(message) },
                                contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp),
                            ) {
                                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(
                                    stringResource(R.string.ask_share_answer),
                                    modifier = Modifier.padding(start = Spacing.xs),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
                // Suggested follow-up questions (P-Rich R3b.2): a settled, non-empty answer on the
                // latest turn offers tappable next questions that re-enter the grounded ask() path.
                if (onFollowUp != null && !message.streaming && !message.error && message.followUps.isNotEmpty()) {
                    FollowUpChips(
                        followUps = message.followUps,
                        enabled = followUpsEnabled,
                        onFollowUp = onFollowUp,
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
                    Text(
                        text =
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("[${citation.index}] ") }
                                append("arXiv:${citation.paperId} — ")
                                // Shared with the Markdown export so shared text matches the screen (R4).
                                append(truncateExcerpt(citation.excerpt))
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

/** Tappable suggested next questions below a settled answer (P-Rich R3b.2); each re-enters ask(). */
@Composable
private fun FollowUpChips(
    followUps: List<String>,
    enabled: Boolean,
    onFollowUp: (String) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        followUps.forEach { question ->
            AssistChip(
                onClick = { onFollowUp(question) },
                enabled = enabled,
                label = {
                    Text(
                        question,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 240.dp),
                    )
                },
            )
        }
    }
}

/** Answer-depth dial (P-Rich R3b): Quick / Standard / Max, applied to the next send or preset. */
@Composable
private fun ModeRow(
    mode: ChatMode,
    enabled: Boolean,
    onSetMode: (ChatMode) -> Unit,
) {
    val options =
        listOf(
            ChatMode.QUICK to R.string.mode_quick,
            ChatMode.STANDARD to R.string.mode_standard,
            ChatMode.MAX to R.string.mode_max,
        )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, labelRes) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onSetMode(value) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) { Text(stringResource(labelRes)) }
        }
    }
}

/**
 * One-tap research-tool presets (P-Rich R3c). A text preset runs its instruction as a grounded
 * question; the vision preset (R3d.4) is routed by the caller to a page picker. A `requiresVision`
 * chip self-disables when no renderable page exists ([pageCount] == 0).
 */
@Composable
private fun PresetRow(
    presets: List<AskPreset>,
    enabled: Boolean,
    pageCount: Int,
    onPresetClick: (AskPreset) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        presets.forEach { preset ->
            val chipEnabled = enabled && (!preset.requiresVision || pageCount > 0)
            AssistChip(
                onClick = { onPresetClick(preset) },
                enabled = chipEnabled,
                label = { Text(stringResource(preset.labelRes)) },
            )
        }
    }
}

/**
 * Page picker for the vision preset (R3d.4). 1-based to the user, default page 1, bounded to
 * [1..pageCount] by a +/- stepper (no free-text parse surface, inherently clamped, TalkBack-friendly).
 * Confirm runs the vision turn for the chosen page (the VM owns the single 0-based conversion — m1).
 */
@Composable
private fun PagePickDialog(
    pageCount: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val maxPage = pageCount.coerceAtLeast(1)
    var page by remember { mutableIntStateOf(1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ask_pick_page_title)) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { page = (page - 1).coerceAtLeast(1) }, enabled = page > 1) {
                    Icon(Icons.Filled.Remove, stringResource(R.string.cd_ask_page_previous))
                }
                Text(
                    stringResource(R.string.ask_pick_page_label, page, maxPage),
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = { page = (page + 1).coerceAtMost(maxPage) }, enabled = page < maxPage) {
                    Icon(Icons.Filled.Add, stringResource(R.string.cd_ask_page_next))
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(page.coerceIn(1, maxPage)) }) {
                Text(stringResource(R.string.ask_pick_page_confirm))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.ask_pick_page_cancel)) }
        },
    )
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
            presets = AskPresets.forScope(isPaper = true),
            onInput = {}, onSend = {}, onRunPreset = {}, onRunVisionPreset = { _, _ -> },
            onRunGraphArtifact = {}, onFollowUp = {},
            onSetMode = {}, onSetIncludeNotes = {},
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
                    visionAvailable = true,
                    pageCount = 8,
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
            presets = AskPresets.forScope(isPaper = true, visionAvailable = true),
            onInput = {}, onSend = {}, onRunPreset = {}, onRunVisionPreset = { _, _ -> },
            onRunGraphArtifact = {}, onFollowUp = {},
            onSetMode = {}, onSetIncludeNotes = {},
            onConfirmSend = {}, onCancelConfirm = {}, onStop = {}, onConfigureProvider = {},
        )
    }
}
