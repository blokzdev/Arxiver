package dev.blokz.arxiver.feature.background

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.sync.BackgroundTask
import dev.blokz.arxiver.sync.TaskKind
import dev.blokz.arxiver.sync.TaskState
import dev.blokz.arxiver.ui.theme.Spacing

/** "Background activity" sheet (UX2.7): live progress for downloads/sync/embedding + cancel/retry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundTasksSheet(
    onDismiss: () -> Unit,
    viewModel: BackgroundTasksViewModel = hiltViewModel(),
) {
    val tasks by viewModel.tasks.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.bg_tasks_title), style = MaterialTheme.typography.titleLarge)

            if (tasks.isEmpty()) {
                Text(
                    stringResource(R.string.bg_tasks_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                tasks.forEach { task ->
                    TaskRow(
                        task = task,
                        onCancel = { viewModel.cancel(task.kind) },
                        onRetry = { viewModel.retry(task.kind) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: BackgroundTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(task.kind.labelRes()),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            when (task.state) {
                is TaskState.Failed ->
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                is TaskState.Running ->
                    if (task.cancellable) {
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                    }
            }
        }
        when (val state = task.state) {
            is TaskState.Running ->
                if (state.progress != null) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            is TaskState.Failed ->
                Text(
                    stringResource(R.string.bg_task_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
        }
    }
}

private fun TaskKind.labelRes(): Int =
    when (this) {
        TaskKind.GEMMA_DOWNLOAD -> R.string.bg_task_gemma_download
        TaskKind.EMBEDDING_MODEL_DOWNLOAD -> R.string.bg_task_model_download
        TaskKind.FOLLOW_SYNC -> R.string.bg_task_follow_sync
        TaskKind.EMBEDDING -> R.string.bg_task_embedding
    }
