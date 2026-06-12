package dev.blokz.arxiver.feature.claude

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.database.entity.RoutineDispatchEntity
import dev.blokz.arxiver.data.DispatchRepository
import dev.blokz.arxiver.ui.components.EmptyState
import dev.blokz.arxiver.ui.components.StatusChip
import dev.blokz.arxiver.ui.components.StatusTone
import dev.blokz.arxiver.ui.theme.Spacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class DispatchHistoryViewModel
    @Inject
    constructor(
        private val dispatchRepository: DispatchRepository,
    ) : ViewModel() {
        val dispatches: StateFlow<List<RoutineDispatchEntity>> =
            dispatchRepository.observeHistory()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        fun retry(id: Long) {
            viewModelScope.launch { dispatchRepository.retry(id) }
        }

        fun delete(id: Long) {
            viewModelScope.launch { dispatchRepository.delete(id) }
        }
    }

private val timestampFormat = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DispatchHistoryScreen(
    onBack: () -> Unit,
    viewModel: DispatchHistoryViewModel = hiltViewModel(),
) {
    val dispatches by viewModel.dispatches.collectAsState()
    var selected by remember { mutableStateOf<RoutineDispatchEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        if (dispatches.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.history_title),
                body = stringResource(R.string.history_empty),
                icon = Icons.Outlined.History,
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
            ) {
                items(dispatches, key = { it.id }) { dispatch ->
                    DispatchRow(dispatch, onClick = { selected = dispatch })
                }
            }
        }
    }

    selected?.let { dispatch ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = {
                Text(stringResource(R.string.history_detail_title, dispatch.action, statusLabel(dispatch.status)))
            },
            text = {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = dispatch.payloadJson,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier =
                            Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(Spacing.sm),
                    )
                }
            },
            confirmButton = {
                if (dispatch.status != RoutineDispatchEntity.STATUS_SENT) {
                    TextButton(
                        onClick = {
                            viewModel.retry(dispatch.id)
                            selected = null
                        },
                    ) { Text(stringResource(R.string.action_retry)) }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(dispatch.id)
                        selected = null
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
        )
    }
}

@Composable
private fun DispatchRow(
    dispatch: RoutineDispatchEntity,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.history_row_title, dispatch.action, dispatch.paperCount),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text =
                    timestampFormat.format(
                        Instant.ofEpochMilli(dispatch.createdAt).atZone(ZoneId.systemDefault()),
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            dispatch.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
            }
        }
        StatusChip(
            text = statusLabel(dispatch.status) + (dispatch.httpCode?.let { " · $it" } ?: ""),
            tone =
                when (dispatch.status) {
                    RoutineDispatchEntity.STATUS_SENT -> StatusTone.Positive
                    RoutineDispatchEntity.STATUS_FAILED -> StatusTone.Error
                    else -> StatusTone.Neutral
                },
        )
    }
}

/** Raw dispatch status values mapped to localized labels. */
@Composable
private fun statusLabel(status: String): String =
    when (status) {
        RoutineDispatchEntity.STATUS_SENT -> stringResource(R.string.history_status_sent)
        RoutineDispatchEntity.STATUS_QUEUED -> stringResource(R.string.history_status_queued)
        RoutineDispatchEntity.STATUS_FAILED -> stringResource(R.string.history_status_failed)
        else -> status
    }

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DispatchRowPreview() {
    dev.blokz.arxiver.ui.theme.ArxiverTheme {
        Column {
            dev.blokz.arxiver.ui.fixtures.PreviewFixtures.dispatches.forEach { dispatch ->
                DispatchRow(dispatch, onClick = {})
            }
        }
    }
}
