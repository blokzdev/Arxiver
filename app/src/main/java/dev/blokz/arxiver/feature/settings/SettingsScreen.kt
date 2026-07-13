package dev.blokz.arxiver.feature.settings

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.BuildConfig
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.ml.ModelState
import dev.blokz.arxiver.data.SettingsRepository
import dev.blokz.arxiver.ui.components.StatusChip
import dev.blokz.arxiver.ui.components.StatusTone
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenRoutines: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenAiProviders: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val trendingEnabled by viewModel.trendingEnabled.collectAsState()
    val rankerHealth by viewModel.rankerHealth.collectAsState()
    val backupJson by viewModel.backupJson.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showBackgroundTasks by remember { mutableStateOf(false) }

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

    // Ambient digest opt-in (PA.1b): enabling on Android 13+ requests POST_NOTIFICATIONS (the app's first-ever
    // ask). Persist only on grant; a denial keeps it off and hints at system settings. Disabling just clears it.
    val digestDeniedMessage = stringResource(R.string.settings_digest_permission_denied)
    val digestNotifPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.setDigestEnabled(true)
            } else {
                scope.launch { snackbar.showSnackbar(digestDeniedMessage) }
            }
        }
    val onSetDigestEnabled: (Boolean) -> Unit = { enabled ->
        when {
            !enabled -> viewModel.setDigestEnabled(false)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                digestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> viewModel.setDigestEnabled(true)
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
        SettingsContent(
            state = state,
            rankerHealth = rankerHealth,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            onSyncInterval = viewModel::setSyncInterval,
            onSetDigestEnabled = onSetDigestEnabled,
            trendingEnabled = trendingEnabled,
            onSetTrendingEnabled = viewModel::setTrendingEnabled,
            onDownloadModel = viewModel::downloadModel,
            onReindex = viewModel::reindex,
            onDeleteModel = viewModel::deleteModel,
            onClearPdfCache = viewModel::clearPdfCache,
            onExportBackup = viewModel::exportBackup,
            onImportBackup = { importPicker.launch(arrayOf("application/json", "text/*")) },
            onOpenRoutines = onOpenRoutines,
            onOpenTemplates = onOpenTemplates,
            onOpenHistory = onOpenHistory,
            onOpenAiProviders = onOpenAiProviders,
            onOpenBackgroundTasks = { showBackgroundTasks = true },
        )
    }

    if (showBackgroundTasks) {
        dev.blokz.arxiver.feature.background.BackgroundTasksSheet(
            onDismiss = { showBackgroundTasks = false },
        )
    }
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    rankerHealth: dev.blokz.arxiver.sync.RankerHealth?,
    modifier: Modifier = Modifier,
    onSyncInterval: (Int) -> Unit,
    onSetDigestEnabled: (Boolean) -> Unit,
    trendingEnabled: Boolean,
    onSetTrendingEnabled: (Boolean) -> Unit,
    onDownloadModel: () -> Unit,
    onReindex: () -> Unit,
    onDeleteModel: () -> Unit,
    onClearPdfCache: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onOpenRoutines: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAiProviders: () -> Unit,
    onOpenBackgroundTasks: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SectionTitle(stringResource(R.string.settings_sync_section), icon = Icons.Outlined.Sync)
        SettingsLink(stringResource(R.string.settings_background_activity), onOpenBackgroundTasks)
        Text(
            stringResource(R.string.settings_sync_interval),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SettingsRepository.SYNC_INTERVAL_CHOICES.forEach { hours ->
                FilterChip(
                    selected = state.syncIntervalHours == hours,
                    onClick = { onSyncInterval(hours) },
                    label = { Text(stringResource(R.string.settings_hours, hours)) },
                )
            }
        }

        // Ambient digest opt-in (PA.1b).
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_digest_title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.settings_digest_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(
                checked = state.digestEnabled,
                onCheckedChange = onSetDigestEnabled,
            )
        }

        // "Emerging in your areas" opt-in (P-Discover2 PD.3b) — plain toggle, no permission (posts nothing).
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_trending_title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.settings_trending_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(
                checked = trendingEnabled,
                onCheckedChange = onSetTrendingEnabled,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SectionTitle(stringResource(R.string.settings_semantic_section), icon = Icons.Outlined.Psychology)
        val (modelLabel, modelTone) =
            when (val model = state.modelState) {
                is ModelState.Ready -> stringResource(R.string.settings_model_ready) to StatusTone.Positive
                is ModelState.Downloading ->
                    stringResource(R.string.settings_model_downloading, model.progressPercent) to StatusTone.Machine
                is ModelState.Failed -> stringResource(R.string.settings_model_failed) to StatusTone.Error
                ModelState.NotDownloaded ->
                    stringResource(R.string.settings_model_absent) to StatusTone.Neutral
            }
        StatusChip(text = modelLabel, tone = modelTone)
        Text(
            pluralStringResource(R.plurals.settings_embedded_count, state.embeddedCount, state.embeddedCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (state.modelState !is ModelState.Ready) {
                TextButton(onClick = onDownloadModel) {
                    Text(stringResource(R.string.settings_model_download))
                }
            } else {
                TextButton(onClick = onReindex) {
                    Text(stringResource(R.string.settings_model_reindex))
                }
                TextButton(onClick = onDeleteModel) {
                    Text(stringResource(R.string.settings_model_delete))
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SectionTitle(stringResource(R.string.settings_storage_section), icon = Icons.Outlined.Storage)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_pdf_cache, state.pdfCacheMb),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClearPdfCache) {
                Text(stringResource(R.string.settings_clear))
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SectionTitle(stringResource(R.string.settings_backup_section), icon = Icons.Outlined.SettingsBackupRestore)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TextButton(onClick = onExportBackup) {
                Text(stringResource(R.string.settings_backup_export))
            }
            TextButton(onClick = onImportBackup) {
                Text(stringResource(R.string.settings_backup_import))
            }
        }
        Text(
            stringResource(R.string.settings_backup_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SectionTitle(stringResource(R.string.settings_ai_section), icon = Icons.Outlined.SmartToy)
        SettingsLink(stringResource(R.string.settings_ai_providers_link), onOpenAiProviders)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SectionTitle(stringResource(R.string.settings_claude_section), icon = Icons.Outlined.AutoAwesome)
        SettingsLink(stringResource(R.string.library_menu_routines), onOpenRoutines)
        SettingsLink(stringResource(R.string.template_catalog_title), onOpenTemplates)
        SettingsLink(stringResource(R.string.library_menu_history), onOpenHistory)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SectionTitle(stringResource(R.string.settings_about_section), icon = Icons.Outlined.Info)
        Text(
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.settings_about_blurb),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Spacing.xl),
        )

        // P5.1: the on-device ranker-eval readout. DEBUG-gated at the call site so R8 strips the card from
        // release; the metrics are computed by the background worker over the user's own labels — never here,
        // never transmitted.
        if (BuildConfig.DEBUG) {
            RankerHealthCard(rankerHealth)
        }
    }
}

@Composable
private fun SectionTitle(
    text: String,
    icon: ImageVector,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = Spacing.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = Spacing.sm),
        )
    }
}

@Composable
private fun SettingsLink(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 48.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsContentPreview() {
    ArxiverTheme {
        SettingsContent(
            state =
                SettingsUiState(
                    modelState = ModelState.Ready(java.io.File("model.onnx")),
                    embeddedCount = 412,
                    pdfCacheMb = 96,
                ),
            rankerHealth = null,
            onSyncInterval = {},
            onSetDigestEnabled = {},
            trendingEnabled = false,
            onSetTrendingEnabled = {},
            onDownloadModel = {},
            onReindex = {},
            onDeleteModel = {},
            onClearPdfCache = {},
            onExportBackup = {},
            onImportBackup = {},
            onOpenRoutines = {},
            onOpenTemplates = {},
            onOpenHistory = {},
            onOpenAiProviders = {},
            onOpenBackgroundTasks = {},
        )
    }
}
