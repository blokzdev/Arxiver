package dev.blokz.arxiver.feature.chat

import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.database.dao.ChatSessionRow
import dev.blokz.arxiver.core.database.entity.ChatSessionEntity
import dev.blokz.arxiver.core.search.RetrievalScope
import dev.blokz.arxiver.data.ChatRepository
import dev.blokz.arxiver.ui.components.EmptyState
import dev.blokz.arxiver.ui.components.SectionHeader
import dev.blokz.arxiver.ui.components.SkeletonList
import dev.blokz.arxiver.ui.feedback.FeedbackAction
import dev.blokz.arxiver.ui.feedback.FeedbackMessage
import dev.blokz.arxiver.ui.feedback.LocalFeedbackController
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A resumable chat session for the history list. */
data class ChatHistoryRow(
    val sessionId: Long,
    val scope: RetrievalScope,
    val isCollection: Boolean,
    /** Resolved paper title / collection name; null if the target was deleted. */
    val label: String?,
    /** User-set custom title (P-Chat PC.5); null = show the derived [label]. */
    val customTitle: String?,
    /** Whether the session is pinned (P-Chat PC.5) — drives the Pinned section + ordering. */
    val pinned: Boolean,
    /** Latest non-empty message (assistant answer, or the user turn on an errored first turn). */
    val snippet: String?,
    val lastMessageAt: Long,
) {
    /** The title to carry to the full-screen route: custom over derived; null = orphan fallback. */
    fun effectiveTitle(): String? = customTitle ?: label
}

@HiltViewModel
class ChatHistoryViewModel
    @Inject
    constructor(
        private val chatRepository: ChatRepository,
    ) : ViewModel() {
        // Sessions the user just deleted, hidden immediately while the undo window runs (PC.3).
        private val pendingDelete = MutableStateFlow<Set<Long>>(emptySet())

        // null = still loading. Labels + snippet resolve in SQL (no per-row paperById N+1).
        val rows: StateFlow<List<ChatHistoryRow>?> =
            combine(chatRepository.observeSessionRows(), pendingDelete) { rows, pending ->
                rows.filterNot { it.session.id in pending }.map { it.toRow() }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        private val _chatDeleted = MutableStateFlow<Long?>(null)

        /** The last session id deleted, so the screen can offer an Undo snackbar once. */
        val chatDeleted: StateFlow<Long?> = _chatDeleted.asStateFlow()

        fun delete(sessionId: Long) {
            pendingDelete.update { it + sessionId } // hide the row now
            chatRepository.scheduleDelete(sessionId) // commit after the undo window (app scope)
            _chatDeleted.value = sessionId
        }

        fun undoDelete(sessionId: Long) {
            chatRepository.undoDelete(sessionId)
            pendingDelete.update { it - sessionId }
        }

        fun consumeChatDeleted() {
            _chatDeleted.value = null
        }

        fun pin(
            sessionId: Long,
            pinned: Boolean,
        ) {
            // Fire-and-forget: Room invalidation re-emits observeSessionRows with the new order.
            viewModelScope.launch { chatRepository.setPinned(sessionId, pinned) }
        }

        fun rename(
            sessionId: Long,
            title: String?,
        ) {
            viewModelScope.launch { chatRepository.renameSession(sessionId, title) }
        }

        private fun ChatSessionRow.toRow(): ChatHistoryRow {
            val isCollection = session.scope == ChatSessionEntity.SCOPE_COLLECTION
            return ChatHistoryRow(
                sessionId = session.id,
                scope =
                    if (isCollection) {
                        RetrievalScope.Collection(session.scopeId.toLongOrNull() ?: -1L)
                    } else {
                        RetrievalScope.Paper(session.scopeId)
                    },
                isCollection = isCollection,
                label = if (isCollection) collectionName else paperTitle,
                customTitle = session.title,
                pinned = session.pinned,
                snippet = snippet,
                lastMessageAt = session.lastMessageAt,
            )
        }
    }

/**
 * The promoted Chat tab (P-Chat PC.3): resumable sessions, most-recently-active first. Hosted
 * as a bottom-tab ([onBack] null hides the back arrow) or — historically — as a stacked screen.
 * Rows resume the full-screen conversation ([onOpenSession]); deleting shows an Undo snackbar
 * and commits after the window. The empty state routes to the Library tab ([onBrowseLibrary]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistoryScreen(
    onOpenSession: (Long, String?) -> Unit,
    onBrowseLibrary: () -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: ChatHistoryViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsState()
    var renameTarget by remember { mutableStateOf<ChatHistoryRow?>(null) }
    val feedback = LocalFeedbackController.current
    val deletedId by viewModel.chatDeleted.collectAsState()
    val deletedText = stringResource(R.string.chat_deleted)
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(deletedId) {
        val id = deletedId ?: return@LaunchedEffect
        feedback.show(
            FeedbackMessage(
                text = deletedText,
                primary = FeedbackAction(undoLabel) { viewModel.undoDelete(id) },
            ),
        )
        viewModel.consumeChatDeleted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_chat)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val current = rows
        when {
            current == null -> SkeletonList(modifier = Modifier.padding(padding))
            current.isEmpty() ->
                EmptyState(
                    title = stringResource(R.string.chat_history_empty),
                    body = stringResource(R.string.chat_history_empty_body),
                    actionLabel = stringResource(R.string.chat_empty_cta),
                    onAction = onBrowseLibrary,
                    modifier = Modifier.padding(padding),
                )
            else -> {
                // The list is already pinned-first (observeSessionRows: pinned DESC, then
                // last_message_at DESC) — split it into sections; an all-recent list shows no
                // header (byte-identical to the flat pre-PC.5 layout). A pin toggle re-parents
                // a row across the boundary, hence Modifier.animateItem() (PC.5).
                val (pinned, recent) = current.partition { it.pinned }
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                ) {
                    if (pinned.isNotEmpty()) {
                        item(key = "header-pinned", contentType = "header") {
                            SectionHeader(stringResource(R.string.chat_section_pinned))
                        }
                    }
                    items(pinned, key = { it.sessionId }, contentType = { "row" }) { row ->
                        ChatHistoryListRow(row, onOpenSession, viewModel) { renameTarget = row }
                    }
                    if (pinned.isNotEmpty() && recent.isNotEmpty()) {
                        item(key = "header-recent", contentType = "header") {
                            SectionHeader(stringResource(R.string.chat_section_recent))
                        }
                    }
                    items(recent, key = { it.sessionId }, contentType = { "row" }) { row ->
                        ChatHistoryListRow(row, onOpenSession, viewModel) { renameTarget = row }
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenameChatDialog(
            initial = target.customTitle ?: target.label ?: "",
            onConfirm = {
                viewModel.rename(target.sessionId, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}

/** One animated recents row + its divider — shared by the Pinned and Recent sections (PC.5). */
@Composable
private fun androidx.compose.foundation.lazy.LazyItemScope.ChatHistoryListRow(
    row: ChatHistoryRow,
    onOpenSession: (Long, String?) -> Unit,
    viewModel: ChatHistoryViewModel,
    onRename: () -> Unit,
) {
    ChatHistoryRowItem(
        row,
        modifier = Modifier.animateItem(),
        onClick = { onOpenSession(row.sessionId, row.effectiveTitle()) },
        onDelete = { viewModel.delete(row.sessionId) },
        onTogglePin = { viewModel.pin(row.sessionId, !row.pinned) },
        onRename = onRename,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ChatHistoryRowItem(
    row: ChatHistoryRow,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 64.dp)
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            val fallback =
                stringResource(
                    if (row.isCollection) {
                        R.string.chat_history_unknown_collection
                    } else {
                        R.string.chat_history_unknown_paper
                    },
                )
            Text(
                row.customTitle ?: row.label ?: fallback,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!row.snippet.isNullOrEmpty()) {
                Text(
                    row.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = onClick,
                    label = {
                        Text(
                            stringResource(
                                if (row.isCollection) {
                                    R.string.chat_history_scope_collection
                                } else {
                                    R.string.chat_history_scope_paper
                                },
                            ),
                        )
                    },
                )
                Text(
                    DateUtils.getRelativeTimeSpanString(row.lastMessageAt).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onTogglePin) {
            Icon(
                if (row.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                stringResource(if (row.pinned) R.string.chat_unpin else R.string.chat_pin),
            )
        }
        Box {
            var menuOpen by remember { mutableStateOf(false) }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, stringResource(R.string.chat_more_actions))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_rename)) },
                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_history_delete)) },
                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/**
 * Rename dialog (P-Chat PC.5), cloning the NewCollectionDialog idiom. Prefilled with the current
 * effective title; a BLANK submit clears the custom title back to the derived label (null).
 * `internal` — shared with [dev.blokz.arxiver.feature.chat.ChatSessionScreen].
 */
@Composable
internal fun RenameChatDialog(
    initial: String,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.chat_rename_hint)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim().takeIf { it.isNotBlank() }) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatHistoryRowPreview() {
    fun row(
        id: Long,
        scope: RetrievalScope,
        isCollection: Boolean,
        label: String?,
        customTitle: String?,
        pinned: Boolean,
        snippet: String?,
    ) = ChatHistoryRow(
        sessionId = id,
        scope = scope,
        isCollection = isCollection,
        label = label,
        customTitle = customTitle,
        pinned = pinned,
        snippet = snippet,
        lastMessageAt = 0L,
    )
    ArxiverTheme {
        Column {
            ChatHistoryRowItem(
                row(
                    1,
                    RetrievalScope.Paper("2401.00001"),
                    false,
                    "Attention Is All You Need",
                    null,
                    true,
                    "Multi-head attention runs layers in parallel.",
                ),
                onClick = {},
                onDelete = {},
                onTogglePin = {},
                onRename = {},
            )
            ChatHistoryRowItem(
                row(
                    2,
                    RetrievalScope.Collection(7),
                    true,
                    "Transformers reading list",
                    "My thesis notes",
                    false,
                    "The three papers share a positional-encoding scheme.",
                ),
                onClick = {},
                onDelete = {},
                onTogglePin = {},
                onRename = {},
            )
            // Orphaned target (paper deleted) + no snippet — fallback label, no snippet line.
            ChatHistoryRowItem(
                row(3, RetrievalScope.Paper("gone"), false, null, null, false, null),
                onClick = {},
                onDelete = {},
                onTogglePin = {},
                onRename = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RenameChatDialogPreview() {
    ArxiverTheme {
        RenameChatDialog(initial = "Attention Is All You Need", onConfirm = {}, onDismiss = {})
    }
}
