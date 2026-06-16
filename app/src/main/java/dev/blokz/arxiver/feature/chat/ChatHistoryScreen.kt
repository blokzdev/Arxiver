package dev.blokz.arxiver.feature.chat

import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.database.entity.ChatSessionEntity
import dev.blokz.arxiver.core.search.RetrievalScope
import dev.blokz.arxiver.data.ChatRepository
import dev.blokz.arxiver.data.LibraryRepository
import dev.blokz.arxiver.feature.paper.ask.AskSheet
import dev.blokz.arxiver.ui.components.EmptyState
import dev.blokz.arxiver.ui.components.SkeletonList
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A resumable chat session for the history list. */
data class ChatHistoryRow(
    val sessionId: Long,
    val scope: RetrievalScope,
    val isCollection: Boolean,
    /** Resolved paper title / collection name; null if the target was deleted. */
    val label: String?,
    val lastMessageAt: Long,
)

@HiltViewModel
class ChatHistoryViewModel
    @Inject
    constructor(
        private val chatRepository: ChatRepository,
        private val paperDao: PaperDao,
        libraryRepository: LibraryRepository,
    ) : ViewModel() {
        // null = still loading.
        val rows: StateFlow<List<ChatHistoryRow>?> =
            combine(
                chatRepository.observeAllSessions(),
                libraryRepository.observeCollections(),
            ) { sessions, collections ->
                val names = collections.associate { it.id to it.name }
                sessions.map { it.toRow(names) }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        fun delete(sessionId: Long) {
            viewModelScope.launch { chatRepository.deleteSession(sessionId) }
        }

        private suspend fun ChatSessionEntity.toRow(collectionNames: Map<Long, String>): ChatHistoryRow =
            if (scope == ChatSessionEntity.SCOPE_COLLECTION) {
                val id = scopeId.toLongOrNull() ?: -1L
                ChatHistoryRow(
                    sessionId = this.id,
                    scope = RetrievalScope.Collection(id),
                    isCollection = true,
                    label = collectionNames[id],
                    lastMessageAt = lastMessageAt,
                )
            } else {
                ChatHistoryRow(
                    sessionId = this.id,
                    scope = RetrievalScope.Paper(scopeId),
                    isCollection = false,
                    label = paperDao.paperById(scopeId)?.title,
                    lastMessageAt = lastMessageAt,
                )
            }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistoryScreen(
    onBack: () -> Unit,
    onOpenAiSettings: () -> Unit,
    viewModel: ChatHistoryViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsState()
    var active by remember { mutableStateOf<ChatHistoryRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chat_history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
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
                    modifier = Modifier.padding(padding),
                )
            else ->
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                ) {
                    items(current, key = { it.sessionId }) { row ->
                        ChatHistoryRowItem(
                            row,
                            onClick = { active = row },
                            onDelete = { viewModel.delete(row.sessionId) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
        }
    }

    active?.let { row ->
        AskSheet(
            scope = row.scope,
            sessionId = row.sessionId,
            onDismiss = { active = null },
            onConfigureProvider = {
                active = null
                onOpenAiSettings()
            },
        )
    }
}

@Composable
private fun ChatHistoryRowItem(
    row: ChatHistoryRow,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
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
                row.label ?: fallback,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, stringResource(R.string.chat_history_delete))
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatHistoryRowPreview() {
    ArxiverTheme {
        Column {
            ChatHistoryRowItem(
                ChatHistoryRow(1, RetrievalScope.Paper("2401.00001"), false, "Attention Is All You Need", 0L),
                onClick = {},
                onDelete = {},
            )
            ChatHistoryRowItem(
                ChatHistoryRow(2, RetrievalScope.Collection(7), true, "Transformers reading list", 0L),
                onClick = {},
                onDelete = {},
            )
        }
    }
}
