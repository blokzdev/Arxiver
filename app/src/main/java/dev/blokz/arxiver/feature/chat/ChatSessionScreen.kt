package dev.blokz.arxiver.feature.chat

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.withStateAtLeast
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.core.search.RetrievalScope
import dev.blokz.arxiver.data.ChatRepository
import dev.blokz.arxiver.feature.paper.ask.AskMessage
import dev.blokz.arxiver.feature.paper.ask.AskPresets
import dev.blokz.arxiver.feature.paper.ask.AskRole
import dev.blokz.arxiver.feature.paper.ask.AskSheetContent
import dev.blokz.arxiver.feature.paper.ask.AskUiState
import dev.blokz.arxiver.feature.paper.ask.ConversationHost
import dev.blokz.arxiver.feature.paper.ask.ConversationMarkdown
import dev.blokz.arxiver.feature.paper.ask.SessionStart
import dev.blokz.arxiver.feature.paper.ask.rememberConversationMarkdownLabels
import dev.blokz.arxiver.ui.shareText
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Route scope-kind segments for [dev.blokz.arxiver.ui.Routes.CHAT_NEW] (P-Chat PC.1). */
internal const val CHAT_SCOPE_KIND_PAPER = "paper"
internal const val CHAT_SCOPE_KIND_COLLECTION = "collection"

/** What the chat route resolved to (PC.1). */
sealed interface ChatSessionUiState {
    data object Loading : ChatSessionUiState

    /** The route args resolved to a live conversation start. */
    data class Ready(val sessionStart: SessionStart, val title: String?) : ChatSessionUiState

    /** Session deleted elsewhere / unparsable args (stale back stack) — leave quietly. */
    data object Missing : ChatSessionUiState
}

/**
 * Resolves the route args into a [SessionStart] once (PC.1): `chat/session/{sessionId}` looks
 * the session up in the DB to reconstruct its scope ([ChatRepository.sessionScope]);
 * `chat/new/{scopeKind}/{scopeId}` builds a [SessionStart.New] directly. Route args arrive as
 * strings (app precedent: FilteredPapersViewModel) — anything unparsable resolves [Missing].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatSessionViewModel
    @Inject
    constructor(
        private val chatRepository: ChatRepository,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        // Scope resolution happens ONCE (the outer flow emits exactly one inner Flow), but for a
        // Resume the title is LIVE — observeSession keeps the TopAppBar in sync with a rename made
        // here or on the history tab (PC.5). flatMapLatest{it} flattens the one-shot outer to it.
        val uiState: StateFlow<ChatSessionUiState> =
            flow {
                // Blank = the builders' encoding of "no title" (the query param is always
                // present so the route pattern matches without navArgument defaults).
                val routeTitle: String? = savedStateHandle.get<String>("title")?.takeIf { it.isNotBlank() }
                val sessionId = savedStateHandle.get<String>("sessionId")?.toLongOrNull()
                if (sessionId != null) {
                    val scope = chatRepository.sessionScope(sessionId)
                    if (scope == null) {
                        emit(flowOf<ChatSessionUiState>(ChatSessionUiState.Missing))
                    } else {
                        val start = SessionStart.Resume(scope, sessionId)
                        // Live title: custom title over the route arg; a null entity (deleted while
                        // open) falls back to the route arg and keeps the screen (does NOT pop —
                        // Missing is only for an initially-unresolvable scope).
                        emit(
                            chatRepository.observeSession(sessionId).map { entity ->
                                ChatSessionUiState.Ready(start, entity?.title ?: routeTitle)
                            },
                        )
                    }
                } else {
                    val scopeId: String? = savedStateHandle["scopeId"]
                    val scope =
                        when {
                            scopeId == null -> null
                            savedStateHandle.get<String>("scopeKind") == CHAT_SCOPE_KIND_PAPER ->
                                RetrievalScope.Paper(scopeId)
                            savedStateHandle.get<String>("scopeKind") == CHAT_SCOPE_KIND_COLLECTION ->
                                scopeId.toLongOrNull()?.let { RetrievalScope.Collection(it) }
                            else -> null
                        }
                    emit(
                        flowOf(
                            scope?.let { ChatSessionUiState.Ready(SessionStart.New(it), routeTitle) }
                                ?: ChatSessionUiState.Missing,
                        ),
                    )
                }
            }.flatMapLatest { it }.stateIn(viewModelScope, SharingStarted.Lazily, ChatSessionUiState.Loading)

        /** Rename the resumed session (P-Chat PC.5); a null title clears back to the derived label. */
        fun rename(title: String?) {
            val id =
                ((uiState.value as? ChatSessionUiState.Ready)?.sessionStart as? SessionStart.Resume)?.sessionId
                    ?: return
            viewModelScope.launch { chatRepository.renameSession(id, title) }
        }
    }

/**
 * Full-screen conversation (P-Chat PC.1): Scaffold + TopAppBar around the same
 * [ConversationHost] the quick-ask sheet wraps. Resume screens offer a "New conversation"
 * fork action — the recorded fork-new-session backlog item, closed here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSessionScreen(
    onBack: () -> Unit,
    onConfigureProvider: () -> Unit,
    onOpenPaper: (String) -> Unit,
    onNewConversation: (RetrievalScope, String?) -> Unit,
    viewModel: ChatSessionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    when (val s = state) {
        // Sub-frame DB read; ConversationHost shows its own hydrating state once Ready.
        ChatSessionUiState.Loading -> Unit
        ChatSessionUiState.Missing -> {
            // Session gone (deleted elsewhere → stale back-stack entry): leave quietly. Wait
            // for RESUMED before popping — firing mid enter-transition (state still STARTED)
            // would either be swallowed or double-pop against an in-flight user back press.
            val owner = LocalLifecycleOwner.current
            LaunchedEffect(Unit) {
                owner.lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {}
                onBack()
            }
        }
        is ChatSessionUiState.Ready -> {
            val start = s.sessionStart
            val context = LocalContext.current
            val exportLabels = rememberConversationMarkdownLabels()
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                s.title ?: stringResource(R.string.chat_session_title),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                            }
                        },
                        actions = {
                            // Rename + fork are on resumed conversations only: a New screen IS the
                            // fresh conversation until its first send (recorded deferral).
                            if (start is SessionStart.Resume) {
                                var showRename by remember { mutableStateOf(false) }
                                IconButton(onClick = { showRename = true }) {
                                    Icon(Icons.Filled.Edit, stringResource(R.string.chat_rename))
                                }
                                if (showRename) {
                                    RenameChatDialog(
                                        initial = s.title ?: "",
                                        onConfirm = {
                                            viewModel.rename(it)
                                            showRename = false
                                        },
                                        onDismiss = { showRename = false },
                                    )
                                }
                                IconButton(onClick = { onNewConversation(start.scope, s.title) }) {
                                    Icon(
                                        Icons.Filled.AddComment,
                                        stringResource(R.string.chat_session_new),
                                    )
                                }
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    Modifier
                        .padding(padding)
                        .consumeWindowInsets(padding)
                        .imePadding(),
                ) {
                    ConversationHost(
                        sessionStart = start,
                        onConfigureProvider = onConfigureProvider,
                        // The TopAppBar owns the title; the export menu stays in-content.
                        headerTitle = null,
                        onOpenCrossRef = onOpenPaper,
                        // Share an answer as Markdown via the OS sheet — mirrors the deleted
                        // history-screen host's wiring (subject = the row label / route title).
                        onShareAnswer = { m ->
                            context.shareText(
                                ConversationMarkdown.answer(m, exportLabels),
                                subject = s.title,
                            )
                        },
                        conversationTitle = s.title,
                        // Pin-to-notes stays sheet-only: it is PaperDetailViewModel.addNote,
                        // VM-private — no host-agnostic seam exists yet (recorded deferral).
                        onPinAnswer = null,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatSessionScreenPreview() {
    ArxiverTheme {
        Scaffold(
            topBar = {
                @OptIn(ExperimentalMaterial3Api::class)
                TopAppBar(
                    title = { Text("Attention Is All You Need", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = {}) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = {}) {
                            Icon(Icons.Filled.AddComment, stringResource(R.string.chat_session_new))
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).consumeWindowInsets(padding)) {
                AskSheetContent(
                    state =
                        AskUiState(
                            provider = ProviderId.ON_DEVICE,
                            isCloud = false,
                            messages =
                                listOf(
                                    AskMessage(AskRole.USER, "What is multi-head attention?"),
                                    AskMessage(
                                        AskRole.ASSISTANT,
                                        "Multi-head attention runs several scaled dot-product " +
                                            "attention layers in parallel and concatenates their outputs.",
                                    ),
                                ),
                        ),
                    // The full-screen host drops the in-content title; the export gate must be
                    // exercised so the preview pins the headerless Spacer + icon row.
                    headerTitle = null,
                    onExportConversation = {},
                    presets = AskPresets.forScope(isPaper = true),
                    onInput = {}, onSend = {}, onRunPreset = {}, onRunVisionPreset = { _, _ -> },
                    onRunGraphArtifact = {}, onFollowUp = {},
                    onSetMode = {}, onSetIncludeNotes = {}, onSetToolsEnabled = {},
                    onConfirmSend = {}, onCancelConfirm = {}, onStop = {}, onConfigureProvider = {},
                )
            }
        }
    }
}
