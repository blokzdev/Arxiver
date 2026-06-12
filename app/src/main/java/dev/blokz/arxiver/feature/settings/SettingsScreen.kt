package dev.blokz.arxiver.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.BuildConfig
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.ml.ModelState
import dev.blokz.arxiver.data.SettingsRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenRoutines: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenTemplates: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val backupJson by viewModel.backupJson.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    backupJson?.let { json ->
        val send =
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "arxiver-backup.json")
                putExtra(android.content.Intent.EXTRA_TEXT, json)
            }
        context.startActivity(android.content.Intent.createChooser(send, "arxiver-backup.json"))
        viewModel.consumeBackup()
    }

    val okPrefixMessage = stringResource(R.string.settings_import_ok)
    val tokensMessage = stringResource(R.string.settings_import_tokens_needed)
    val failedMessage = stringResource(R.string.settings_import_failed)
    importResult?.let { result ->
        scope.launch {
            val message =
                if (result.startsWith("ok:")) {
                    val needTokens = result.substringAfterLast(':').toIntOrNull() ?: 0
                    if (needTokens > 0) "$okPrefixMessage $tokensMessage" else okPrefixMessage
                } else {
                    failedMessage
                }
            snackbar.showSnackbar(message)
            viewModel.consumeImportResult()
        }
    }

    val importPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri?.let {
                val content =
                    context.contentResolver.openInputStream(it)
                        ?.bufferedReader()?.use { reader -> reader.readText() }
                if (content != null) viewModel.importBackup(content)
            }
        }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_sync_section))
            Text(
                stringResource(R.string.settings_sync_interval),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsRepository.SYNC_INTERVAL_CHOICES.forEach { hours ->
                    FilterChip(
                        selected = state.syncIntervalHours == hours,
                        onClick = { viewModel.setSyncInterval(hours) },
                        label = { Text(stringResource(R.string.settings_hours, hours)) },
                    )
                }
            }

            HorizontalDivider()

            SectionTitle(stringResource(R.string.settings_semantic_section))
            val modelLabel =
                when (val model = state.modelState) {
                    is ModelState.Ready -> stringResource(R.string.settings_model_ready)
                    is ModelState.Downloading ->
                        stringResource(R.string.settings_model_downloading, model.progressPercent)
                    is ModelState.Failed -> stringResource(R.string.settings_model_failed)
                    ModelState.NotDownloaded -> stringResource(R.string.settings_model_absent)
                }
            Text(modelLabel, style = MaterialTheme.typography.bodyMedium)
            Text(
                pluralStringResource(R.plurals.settings_embedded_count, state.embeddedCount, state.embeddedCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.modelState !is ModelState.Ready) {
                    TextButton(onClick = viewModel::downloadModel) {
                        Text(stringResource(R.string.settings_model_download))
                    }
                } else {
                    TextButton(onClick = viewModel::reindex) {
                        Text(stringResource(R.string.settings_model_reindex))
                    }
                    TextButton(onClick = viewModel::deleteModel) {
                        Text(stringResource(R.string.settings_model_delete))
                    }
                }
            }

            HorizontalDivider()

            SectionTitle(stringResource(R.string.settings_storage_section))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.settings_pdf_cache, state.pdfCacheMb),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::clearPdfCache) {
                    Text(stringResource(R.string.settings_clear))
                }
            }

            HorizontalDivider()

            SectionTitle(stringResource(R.string.settings_backup_section))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = viewModel::exportBackup) {
                    Text(stringResource(R.string.settings_backup_export))
                }
                TextButton(onClick = { importPicker.launch(arrayOf("application/json", "text/*")) }) {
                    Text(stringResource(R.string.settings_backup_import))
                }
            }
            Text(
                stringResource(R.string.settings_backup_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            SectionTitle(stringResource(R.string.settings_claude_section))
            SettingsLink(stringResource(R.string.library_menu_routines), onOpenRoutines)
            SettingsLink(stringResource(R.string.template_catalog_title), onOpenTemplates)
            SettingsLink(stringResource(R.string.library_menu_history), onOpenHistory)

            HorizontalDivider()

            SectionTitle(stringResource(R.string.settings_about_section))
            Text(
                stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.settings_about_blurb),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SettingsLink(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
    )
}
