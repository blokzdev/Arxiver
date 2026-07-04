package dev.blokz.arxiver.feature.paper.ask

import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.blokz.arxiver.R
import dev.blokz.arxiver.chat.ChatMode
import dev.blokz.arxiver.chat.ChatPreview
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.core.search.RetrievalScope
import dev.blokz.arxiver.data.Citation
import dev.blokz.arxiver.ui.EXPORT_DIR
import dev.blokz.arxiver.ui.markdown.ConversationPdfPrinter
import dev.blokz.arxiver.ui.markdown.MarkdownText
import dev.blokz.arxiver.ui.markdown.RichBlockWebView
import dev.blokz.arxiver.ui.markdown.RichContent
import dev.blokz.arxiver.ui.markdown.RichImageExporter
import dev.blokz.arxiver.ui.markdown.rememberRichTheme
import dev.blokz.arxiver.ui.shareFile
import dev.blokz.arxiver.ui.shareImage
import dev.blokz.arxiver.ui.shareText
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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
    /** Display title for the whole-conversation export (paper title / collection name / session
     *  label) — used as the share subject + the Markdown `# header` (P-Share PS.6). null still
     *  exports, just without the header line. */
    conversationTitle: String? = null,
    /** A consume-once quote offer (PH.7 reader selection→Ask); idempotent by [AskQuoteRequest.id]. */
    initialQuote: AskQuoteRequest? = null,
    viewModel: AskViewModel = hiltViewModel(),
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ConversationHost(
            sessionStart =
                sessionId?.let { SessionStart.Resume(scope, it) }
                    ?: SessionStart.MostRecentFor(scope),
            onConfigureProvider = onConfigureProvider,
            headerTitle = stringResource(R.string.ask_title),
            onOpenCrossRef = onOpenCrossRef,
            onPinAnswer = onPinAnswer,
            onShareAnswer = onShareAnswer,
            conversationTitle = conversationTitle,
            initialQuote = initialQuote,
            viewModel = viewModel,
        )
    }
}

/**
 * The host-agnostic conversation surface (P-Chat PC.1): state collection, session binding,
 * read-aloud, copy/quote, per-answer PNG export, the whole-conversation export, presets —
 * zero sheet coupling. [AskSheet] wraps it in a ModalBottomSheet for the quick-ask hosts;
 * ChatSessionScreen hosts it full-screen. A null [headerTitle] drops the in-content title
 * line (the full screen's TopAppBar owns the title) while the export menu stays INSIDE the
 * content, so its closures never hoist into a host chrome.
 */
@Composable
internal fun ConversationHost(
    sessionStart: SessionStart,
    onConfigureProvider: () -> Unit,
    headerTitle: String? = null,
    onOpenCrossRef: ((String) -> Unit)? = null,
    onPinAnswer: ((String) -> Unit)? = null,
    onShareAnswer: ((AskMessage) -> Unit)? = null,
    conversationTitle: String? = null,
    initialQuote: AskQuoteRequest? = null,
    viewModel: AskViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(sessionStart) { viewModel.start(sessionStart) }
    LaunchedEffect(initialQuote) { initialQuote?.let(viewModel::offerQuote) }

    // Read-aloud (P-Share PS.2): keyed by answer content so the play/stop toggle survives recomposition;
    // the speakable form is extracted at the UI edge (labels are localized strings).
    val speakingKey by viewModel.speaking.collectAsState()
    val speakableLabels = rememberSpeakableLabels()
    val onReadAloud: (AskMessage) -> Unit = { m ->
        viewModel.toggleReadAloud(m.text.hashCode().toString(), SpeakableText.forAnswer(m.text, speakableLabels))
    }
    // Per-bubble Copy (the markdown answer) + Quote (a blockquote of it, prepended into the input).
    val clipboard = LocalClipboardManager.current
    val onCopy: (AskMessage) -> Unit = { m -> clipboard.setText(AnnotatedString(m.text)) }
    val onQuote: (AskMessage) -> Unit = { m -> viewModel.setInput(quoteInto(m.text, state.input)) }
    // Export a rendered answer to a PNG (P-Share PS.4b) — off-screen WebView capture → share sheet.
    // User-initiated, OS-sheet only, no upload, the AI key is never involved. A Toast covers the
    // few-second render; a null file (no Activity / render never settled) falls back to a Toast.
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    val richTheme = rememberRichTheme()
    val exportRendering = stringResource(R.string.ask_export_rendering)
    val exportFailed = stringResource(R.string.ask_export_failed)
    val exportSubject = stringResource(R.string.ask_export_subject)
    val onExportImage: (AskMessage) -> Unit = { m ->
        Toast.makeText(context, exportRendering, Toast.LENGTH_SHORT).show()
        exportScope.launch {
            val file = RichImageExporter.capture(context, m.text, richTheme)
            if (file != null) {
                context.shareImage(file, exportSubject)
            } else {
                Toast.makeText(context, exportFailed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    // Whole-conversation export (P-Share PS.6): share as Markdown text, export a Markdown *file*, or
    // print/save-as-PDF. Built here (like onExportImage) so every Ask host inherits it for free — the
    // host only supplies the display [conversationTitle]. User-initiated, OS-sheet/print only, no
    // upload, the AI key is never involved; the PDF reuses the offline rich renderer (PS.4b).
    val conversationLabels = rememberConversationMarkdownLabels()
    val convMdPreparing = stringResource(R.string.ask_export_conversation_md_preparing)
    val convPdfPreparing = stringResource(R.string.ask_export_conversation_pdf_preparing)
    val convFailed = stringResource(R.string.ask_export_conversation_failed)
    val convJobName = stringResource(R.string.ask_export_conversation_job)
    val onExportConversation: (ConversationExportKind) -> Unit = { kind ->
        val md = ConversationMarkdown.conversation(state.messages, conversationTitle, conversationLabels)
        when (kind) {
            ConversationExportKind.SHARE_TEXT -> context.shareText(md, subject = conversationTitle)
            ConversationExportKind.MARKDOWN_FILE -> {
                Toast.makeText(context, convMdPreparing, Toast.LENGTH_SHORT).show()
                exportScope.launch {
                    val file = writeConversationMarkdown(context, md)
                    if (file != null) {
                        context.shareFile(file, "text/markdown", subject = conversationTitle)
                    } else {
                        Toast.makeText(context, convFailed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            ConversationExportKind.PDF -> {
                Toast.makeText(context, convPdfPreparing, Toast.LENGTH_SHORT).show()
                exportScope.launch {
                    if (!ConversationPdfPrinter.print(context, md, convJobName)) {
                        Toast.makeText(context, convFailed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    // Stop reading when the sheet is dismissed or the app leaves the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) viewModel.stopReadAloud()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopReadAloud()
        }
    }
    // The "what you can ask" presets, filtered to this scope (paper drops collection-only and
    // vice versa) — P-Rich R3c. The vision preset (R3d.4) is additionally gated on
    // state.visionAvailable, which the VM resolves after open — so re-key on it.
    val presets =
        remember(sessionStart.scope, state.visionAvailable) {
            AskPresets.forScope(
                sessionStart.scope is RetrievalScope.Paper,
                visionAvailable = state.visionAvailable,
            )
        }

    AskSheetContent(
        state = state,
        headerTitle = headerTitle,
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
        onExportConversation = onExportConversation,
        onReadAloud = onReadAloud,
        speakingKey = speakingKey,
        onCopy = onCopy,
        onQuote = onQuote,
        onExportImage = onExportImage,
    )
}

/** What "export this conversation" produces (P-Share PS.6): a text share, a Markdown file, or a PDF. */
enum class ConversationExportKind { SHARE_TEXT, MARKDOWN_FILE, PDF }

/** Write [markdown] to a `.md` file in the shared cache export dir; null on failure (off the main thread). */
private suspend fun writeConversationMarkdown(
    context: Context,
    markdown: String,
): File? =
    withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            val file = File(dir, "conversation_${System.currentTimeMillis()}.md")
            FileOutputStream(file).use { it.write(markdown.toByteArray()) }
            file
        }.getOrNull()
    }

@Composable
internal fun rememberConversationMarkdownLabels(): ConversationMarkdownLabels =
    ConversationMarkdownLabels(
        you = stringResource(R.string.ask_export_you),
        assistant = stringResource(R.string.ask_export_assistant),
        sources = stringResource(R.string.ask_export_sources),
        footer = stringResource(R.string.ask_export_footer),
    )

@Composable
private fun rememberSpeakableLabels(): SpeakableLabels =
    SpeakableLabels(
        diagram = stringResource(R.string.speak_diagram),
        equation = stringResource(R.string.speak_equation),
        image = stringResource(R.string.speak_image),
        code = stringResource(R.string.speak_code),
    )

@Composable
internal fun AskSheetContent(
    state: AskUiState,
    /** In-content title line; null on the full-screen host whose TopAppBar owns the title (PC.1). */
    headerTitle: String?,
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
    onExportConversation: ((ConversationExportKind) -> Unit)? = null,
    onReadAloud: ((AskMessage) -> Unit)? = null,
    speakingKey: String? = null,
    onCopy: ((AskMessage) -> Unit)? = null,
    onQuote: ((AskMessage) -> Unit)? = null,
    onExportImage: ((AskMessage) -> Unit)? = null,
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
        // Export the whole conversation (P-Share PS.6) — shown once a real answer exists. The share
        // icon opens a menu: share as Markdown text, export a Markdown file, or print/save as PDF.
        val canExportConversation =
            onExportConversation != null &&
                state.messages.any {
                    it.role == AskRole.ASSISTANT && !it.streaming && !it.error && it.text.isNotBlank()
                }
        // The full-screen host passes headerTitle = null — its TopAppBar owns the title; the
        // export menu stays in-content either way (PC.1: no closure hoisting into hosts).
        if (headerTitle != null || canExportConversation) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (headerTitle != null) {
                    Text(
                        headerTitle,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (canExportConversation) {
                    var exportMenuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { exportMenuOpen = true }) {
                            Icon(Icons.Filled.Share, stringResource(R.string.cd_ask_share_conversation))
                        }
                        DropdownMenu(
                            expanded = exportMenuOpen,
                            onDismissRequest = { exportMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ask_export_conversation_text)) },
                                leadingIcon = { Icon(Icons.Filled.Share, null) },
                                onClick = {
                                    exportMenuOpen = false
                                    onExportConversation!!(ConversationExportKind.SHARE_TEXT)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ask_export_conversation_markdown)) },
                                leadingIcon = { Icon(Icons.Filled.Description, null) },
                                onClick = {
                                    exportMenuOpen = false
                                    onExportConversation!!(ConversationExportKind.MARKDOWN_FILE)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ask_export_conversation_pdf)) },
                                leadingIcon = { Icon(Icons.Filled.PictureAsPdf, null) },
                                onClick = {
                                    exportMenuOpen = false
                                    onExportConversation!!(ConversationExportKind.PDF)
                                },
                            )
                        }
                    }
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

        // Hydration-in-flight is neither "empty" nor "preparing" (PC.1): resuming a long
        // session must not flash the empty hint first (a spinner stands in). The preset row
        // is standing chrome above the composer and deliberately stays visible throughout.
        if (state.hydrating && state.messages.isEmpty()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
        if (state.messages.isEmpty() && !state.preparing && !state.hydrating) {
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
                    onReadAloud = onReadAloud,
                    isSpeaking = speakingKey != null && speakingKey == message.text.hashCode().toString(),
                    onCopy = onCopy,
                    onQuote = onQuote,
                    onExportImage = onExportImage,
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
    onReadAloud: ((AskMessage) -> Unit)? = null,
    isSpeaking: Boolean = false,
    onCopy: ((AskMessage) -> Unit)? = null,
    onQuote: ((AskMessage) -> Unit)? = null,
    onExportImage: ((AskMessage) -> Unit)? = null,
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
                // User questions gain a single Copy action (P-Share PS.3); errors stay action-free.
                if (isUser && onCopy != null && message.text.isNotBlank()) {
                    BubbleActions(message = message, onCopy = onCopy)
                }
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
                // Per-bubble contextual actions (P-Share PS.3): a compact inline row (Copy · Read-aloud ·
                // Share) + an overflow menu (Pin-to-notes · Quote) under a settled, non-empty answer.
                val canAct = !message.streaming && !message.error && message.text.isNotBlank()
                if (canAct && onCopy != null) {
                    BubbleActions(
                        message = message,
                        onCopy = onCopy,
                        onShareAnswer = onShareAnswer,
                        onPinAnswer = onPinAnswer,
                        onReadAloud = onReadAloud,
                        isSpeaking = isSpeaking,
                        onQuote = onQuote,
                        // Export-as-image only for answers that actually carry a diagram/equation/vector
                        // (the "charts/graphs/artifacts" worth a PNG); prose has Copy + Share-as-Markdown.
                        onExportImage = if (RichContent.has(body)) onExportImage else null,
                    )
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

/**
 * The per-bubble contextual-actions surface (P-Share PS.3): a compact inline icon row of the
 * most-used actions + an overflow ⋮ menu for the rest. User questions pass only [onCopy] (→ a lone
 * Copy); assistant answers pass the full set. Each callback being null hides its action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BubbleActions(
    message: AskMessage,
    onCopy: (AskMessage) -> Unit,
    onShareAnswer: ((AskMessage) -> Unit)? = null,
    onPinAnswer: ((String) -> Unit)? = null,
    onReadAloud: ((AskMessage) -> Unit)? = null,
    isSpeaking: Boolean = false,
    onQuote: ((AskMessage) -> Unit)? = null,
    onExportImage: ((AskMessage) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var pinned by remember(message.text) { mutableStateOf(false) }
    val hasOverflow = onPinAnswer != null || onQuote != null || onExportImage != null

    Row(verticalAlignment = Alignment.CenterVertically) {
        ActionIcon(Icons.Filled.ContentCopy, R.string.ask_copy) { onCopy(message) }
        if (onReadAloud != null) {
            ActionIcon(
                if (isSpeaking) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                if (isSpeaking) R.string.ask_stop_reading else R.string.ask_read_aloud,
            ) { onReadAloud(message) }
        }
        if (onShareAnswer != null) {
            ActionIcon(Icons.Filled.Share, R.string.ask_share_answer) { onShareAnswer(message) }
        }
        if (hasOverflow) {
            Box {
                ActionIcon(Icons.Filled.MoreVert, R.string.ask_more_actions) { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (onPinAnswer != null) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (pinned) R.string.ask_pinned_to_notes else R.string.ask_pin_to_notes,
                                    ),
                                )
                            },
                            enabled = !pinned,
                            leadingIcon = { Icon(if (pinned) Icons.Filled.Check else Icons.Outlined.PushPin, null) },
                            onClick = {
                                menuOpen = false
                                onPinAnswer(message.text)
                                pinned = true
                            },
                        )
                    }
                    if (onQuote != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ask_quote)) },
                            leadingIcon = { Icon(Icons.Filled.FormatQuote, null) },
                            onClick = {
                                menuOpen = false
                                onQuote(message)
                            },
                        )
                    }
                    if (onExportImage != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ask_export_image)) },
                            leadingIcon = { Icon(Icons.Filled.Image, null) },
                            onClick = {
                                menuOpen = false
                                onExportImage(message)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionIcon(
    icon: ImageVector,
    labelRes: Int,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, contentDescription = stringResource(labelRes), modifier = Modifier.size(18.dp))
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
            headerTitle = stringResource(R.string.ask_title),
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
            headerTitle = stringResource(R.string.ask_title),
            presets = AskPresets.forScope(isPaper = true, visionAvailable = true),
            onInput = {}, onSend = {}, onRunPreset = {}, onRunVisionPreset = { _, _ -> },
            onRunGraphArtifact = {}, onFollowUp = {},
            onSetMode = {}, onSetIncludeNotes = {},
            onConfirmSend = {}, onCancelConfirm = {}, onStop = {}, onConfigureProvider = {},
        )
    }
}
