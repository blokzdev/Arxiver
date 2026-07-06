package dev.blokz.arxiver.feature.settings

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.ai.InferenceTier
import dev.blokz.arxiver.core.ai.NanoDownloadProgress
import dev.blokz.arxiver.core.ai.NanoStatus
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.core.ml.ModelState
import dev.blokz.arxiver.ui.components.StatusChip
import dev.blokz.arxiver.ui.components.StatusTone
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import dev.blokz.arxiver.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProviderSettingsScreen(
    onBack: () -> Unit,
    viewModel: AiProviderSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // "Model ready" snackbar on the Downloading→Ready edge (PA.6 follow-up): the reporting user
    // had zero in-app completion signal — the parent SettingsScreen's host is a different
    // destination, so this screen carries its own.
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.modelReadyEvents.collect { nameRes ->
            snackbarHostState.showSnackbar(
                context.getString(R.string.ai_ondevice_ready_snackbar, context.getString(nameRes)),
            )
        }
    }

    // The model download runs as a foreground service with a progress notification (UX2.8). On
    // Android 13+ we ask for POST_NOTIFICATIONS first, then download regardless of the answer
    // (a denial only suppresses the notification — the download still runs).
    // Which tier's download is awaiting the permission result (Gemma or the light Qwen tier; PA.3b).
    var pendingDownloadTier by remember { mutableStateOf<InferenceTier?>(null) }
    val notifPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            pendingDownloadTier?.let { viewModel.downloadModel(it) }
            pendingDownloadTier = null
        }
    val downloadWithNotifPermission: (InferenceTier) -> Unit = { tier ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pendingDownloadTier = tier
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.downloadModel(tier)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_providers_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        AiProviderSettingsContent(
            state = state,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            onSaveKey = viewModel::saveKey,
            onClearKey = viewModel::clearKey,
            onTestConnection = viewModel::testConnection,
            onSelectDefault = viewModel::selectDefault,
            onDownloadModel = downloadWithNotifPermission,
            onDeleteModel = viewModel::deleteModel,
            onDownloadNano = viewModel::downloadNano,
            onSetPreferredTier = viewModel::setPreferredOnDeviceTier,
            onSetPreferOnDeviceWhenReady = viewModel::setPreferOnDeviceWhenReady,
            onSaveS2Key = viewModel::saveS2Key,
            onClearS2Key = viewModel::clearS2Key,
        )
    }
}

@Composable
private fun AiProviderSettingsContent(
    state: AiProviderSettingsUiState,
    modifier: Modifier = Modifier,
    onSaveKey: (ProviderId, String) -> Unit,
    onClearKey: (ProviderId) -> Unit,
    onTestConnection: (ProviderId, InferenceTier?) -> Unit,
    onSelectDefault: (ProviderId) -> Unit,
    onDownloadModel: (InferenceTier) -> Unit,
    onDeleteModel: (InferenceTier) -> Unit,
    onDownloadNano: () -> Unit,
    onSetPreferredTier: (InferenceTier?) -> Unit,
    onSetPreferOnDeviceWhenReady: (Boolean) -> Unit,
    onSaveS2Key: (String) -> Unit,
    onClearS2Key: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            stringResource(R.string.ai_providers_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        state.rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ProviderCard(
                row = row,
                isDefault = state.selectedDefault == row.id,
                onSaveKey = { key -> onSaveKey(row.id, key) },
                onClearKey = { onClearKey(row.id) },
                onTestConnection = { tier -> onTestConnection(row.id, tier) },
                onSelectDefault = { onSelectDefault(row.id) },
                onDownloadModel = onDownloadModel,
                onDeleteModel = onDeleteModel,
                onDownloadNano = onDownloadNano,
                onSetPreferredTier = onSetPreferredTier,
                onSetPreferOnDeviceWhenReady = onSetPreferOnDeviceWhenReady,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SemanticScholarKeySection(
            configured = state.s2KeyConfigured,
            onSaveKey = onSaveS2Key,
            onClearKey = onClearS2Key,
        )
        Text(
            stringResource(R.string.ai_providers_privacy_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = Spacing.lg),
        )
    }
}

/**
 * The OPTIONAL Semantic Scholar BYOK key card (P-Tools PT.3). NOT a provider card: S2 backs the
 * `search_semantic_scholar` tool, not chat — so there is no "Test connection" (no chat ping) and no
 * default radio. The free tier works keyless; a key only lifts rate pressure. Key entry is write-only
 * (masked, never read back), mirroring [CloudKeySection].
 */
@Composable
private fun SemanticScholarKeySection(
    configured: Boolean,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
) {
    var keyInput by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.ai_provider_semantic_scholar),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (configured) {
                StatusChip(stringResource(R.string.ai_provider_connected), tone = StatusTone.Positive)
            }
        }
        Text(
            stringResource(R.string.s2_key_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text(stringResource(R.string.s2_key_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(
                onClick = {
                    onSaveKey(keyInput)
                    keyInput = ""
                },
                enabled = keyInput.isNotBlank(),
            ) {
                Text(stringResource(R.string.ai_provider_save_key))
            }
            if (configured) {
                TextButton(onClick = onClearKey) {
                    Text(stringResource(R.string.ai_provider_clear_key))
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    row: ProviderRow,
    isDefault: Boolean,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onTestConnection: (InferenceTier?) -> Unit,
    onSelectDefault: () -> Unit,
    onDownloadModel: (InferenceTier) -> Unit,
    onDeleteModel: (InferenceTier) -> Unit,
    onDownloadNano: () -> Unit,
    onSetPreferredTier: (InferenceTier?) -> Unit,
    onSetPreferOnDeviceWhenReady: (Boolean) -> Unit,
) {
    val onDevice = row.onDevice
    val usable = if (onDevice != null) onDevice.isUsable else row.configured

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                providerName(row.id),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            StatusChip(
                stringResource(if (usable) R.string.ai_provider_connected else R.string.ai_provider_not_connected),
                tone = if (usable) StatusTone.Positive else StatusTone.Neutral,
            )
        }

        if (onDevice != null) {
            OnDeviceSection(
                info = onDevice,
                onTestConnection = onTestConnection,
                onDownloadModel = onDownloadModel,
                onDeleteModel = onDeleteModel,
                onDownloadNano = onDownloadNano,
                onSetPreferredTier = onSetPreferredTier,
                onSetPreferOnDeviceWhenReady = onSetPreferOnDeviceWhenReady,
            )
        } else {
            CloudKeySection(
                configured = row.configured,
                onSaveKey = onSaveKey,
                onClearKey = onClearKey,
                onTestConnection = { onTestConnection(null) },
            )
        }

        // On-device Test results render inside each ModelCard (per-tier); the shared row is
        // cloud-only — a single shared result once let the Qwen card show a Gemma-served Success.
        if (onDevice == null) TestResult(row.test)

        if (usable) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RadioButton(selected = isDefault, onClick = onSelectDefault)
                Text(
                    stringResource(R.string.ai_provider_use_default),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = Spacing.xs),
                )
            }
        }
    }
}

@Composable
private fun CloudKeySection(
    configured: Boolean,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onTestConnection: () -> Unit,
) {
    var keyInput by remember { mutableStateOf("") }
    // Keys are write-only (SPEC-AI-PROVIDERS §4): masked, never shown back.
    OutlinedTextField(
        value = keyInput,
        onValueChange = { keyInput = it },
        label = { Text(stringResource(R.string.ai_provider_key_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Button(
            onClick = {
                onSaveKey(keyInput)
                keyInput = ""
            },
            enabled = keyInput.isNotBlank(),
        ) {
            Text(stringResource(R.string.ai_provider_save_key))
        }
        if (configured) {
            TextButton(onClick = onTestConnection) {
                Text(stringResource(R.string.ai_provider_test))
            }
            TextButton(onClick = onClearKey) {
                Text(stringResource(R.string.ai_provider_clear_key))
            }
        }
    }
}

@Composable
private fun OnDeviceSection(
    info: OnDeviceInfo,
    onTestConnection: (InferenceTier?) -> Unit,
    onDownloadModel: (InferenceTier) -> Unit,
    onDeleteModel: (InferenceTier) -> Unit,
    onDownloadNano: () -> Unit,
    onSetPreferredTier: (InferenceTier?) -> Unit,
    onSetPreferOnDeviceWhenReady: (Boolean) -> Unit,
) {
    Text(
        stringResource(R.string.ai_ondevice_ram, info.totalRamMb),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(R.string.ai_ondevice_tier, tierLabel(info.recommendedTier)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Gemma (≥4 GB) and the light Qwen tier (≥3 GB) are two independent downloads (P-Atlas PA.3b).
    ModelCard(
        labelRes = R.string.ai_tier_gemma,
        descriptionRes = R.string.ai_ondevice_desc_gemma,
        downloadLabelRes = R.string.ai_ondevice_download,
        // Point an ineligible-for-Gemma device at the lighter Qwen only when it actually qualifies.
        ineligibleLabelRes =
            if (info.lightEligible) {
                R.string.ai_ondevice_ineligible_gemma_light_ok
            } else {
                R.string.ai_ondevice_ineligible_gemma
            },
        state = info.gemmaState,
        eligible = info.gemmaEligible,
        testState = info.tierTests[InferenceTier.GEMMA] ?: ConnectionTest.Idle,
        onTestConnection = { onTestConnection(InferenceTier.GEMMA) },
        onDownload = { onDownloadModel(InferenceTier.GEMMA) },
        onDelete = { onDeleteModel(InferenceTier.GEMMA) },
    )
    ModelCard(
        labelRes = R.string.ai_tier_light,
        descriptionRes = R.string.ai_ondevice_desc_light,
        downloadLabelRes = R.string.ai_ondevice_download_light,
        ineligibleLabelRes = R.string.ai_ondevice_ineligible_light,
        state = info.lightState,
        eligible = info.lightEligible,
        testState = info.tierTests[InferenceTier.LIGHT] ?: ConnectionTest.Idle,
        onTestConnection = { onTestConnection(InferenceTier.LIGHT) },
        onDownload = { onDownloadModel(InferenceTier.LIGHT) },
        onDelete = { onDeleteModel(InferenceTier.LIGHT) },
    )

    NanoSection(info = info, onDownloadNano = onDownloadNano)

    val gemmaReady = info.gemmaState is ModelState.Ready
    val lightReady = info.lightState is ModelState.Ready
    val nanoReady = info.nanoStatus == NanoStatus.AVAILABLE

    // Choose which on-device engine to use when more than one is ready.
    if (listOf(gemmaReady, lightReady, nanoReady).count { it } >= 2) {
        Text(stringResource(R.string.ai_ondevice_prefer), style = MaterialTheme.typography.labelLarge)
        // Short engine names — the ai_tier_* labels embed "(downloaded)"/"(system)" and wrap chips
        // over multiple lines (seen on-device, PA.6 follow-up).
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PreferChip(R.string.ai_prefer_auto, info.preferredTier == null) { onSetPreferredTier(null) }
            if (gemmaReady) {
                PreferChip(R.string.ai_engine_gemma, info.preferredTier == InferenceTier.GEMMA) {
                    onSetPreferredTier(InferenceTier.GEMMA)
                }
            }
            if (lightReady) {
                PreferChip(R.string.ai_engine_light, info.preferredTier == InferenceTier.LIGHT) {
                    onSetPreferredTier(InferenceTier.LIGHT)
                }
            }
            if (nanoReady) {
                PreferChip(R.string.ai_engine_nano, info.preferredTier == InferenceTier.NANO) {
                    onSetPreferredTier(InferenceTier.NANO)
                }
            }
        }
    }

    // Privacy/cost opt-in: prefer on-device over the selected cloud provider when ready.
    if (gemmaReady || lightReady || nanoReady) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.ai_prefer_ondevice_when_ready),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.ai_prefer_ondevice_when_ready_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = info.preferOnDeviceWhenReady,
                onCheckedChange = onSetPreferOnDeviceWhenReady,
            )
        }
    }
}

/** One downloadable on-device model's state machine: download → progress → test/delete (P-Atlas PA.3b). */
@Composable
private fun ModelCard(
    labelRes: Int,
    descriptionRes: Int,
    downloadLabelRes: Int,
    ineligibleLabelRes: Int,
    state: ModelState,
    eligible: Boolean,
    testState: ConnectionTest,
    onTestConnection: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
    Text(
        stringResource(descriptionRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when (state) {
        ModelState.NotDownloaded ->
            if (eligible) {
                Button(onClick = onDownload) {
                    Text(stringResource(downloadLabelRes))
                }
            } else {
                Text(
                    stringResource(ineligibleLabelRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        is ModelState.Downloading ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    stringResource(R.string.ai_ondevice_downloading, state.progressPercent),
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { state.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        is ModelState.Ready ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TextButton(onClick = onTestConnection) {
                    Text(stringResource(R.string.ai_provider_test))
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.ai_ondevice_delete))
                }
            }
        is ModelState.Failed ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    stringResource(R.string.ai_ondevice_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onDownload) {
                    Text(stringResource(R.string.ai_ondevice_retry))
                }
            }
    }
    // This model's own Test result (pinned to its engine — never another tier's).
    TestResult(testState)
}

@Composable
private fun NanoSection(
    info: OnDeviceInfo,
    onDownloadNano: () -> Unit,
) {
    Text(stringResource(R.string.ai_tier_nano), style = MaterialTheme.typography.labelLarge)
    info.nanoDownload?.let { progress ->
        when (progress) {
            is NanoDownloadProgress.Downloading ->
                Text(
                    progress.percent?.let { stringResource(R.string.ai_nano_downloading_pct, it) }
                        ?: stringResource(R.string.ai_nano_downloading),
                    style = MaterialTheme.typography.bodySmall,
                )
            is NanoDownloadProgress.Failed ->
                Text(
                    stringResource(R.string.ai_nano_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            NanoDownloadProgress.Done -> Unit
        }
        return
    }
    when (info.nanoStatus) {
        NanoStatus.AVAILABLE ->
            StatusChip(stringResource(R.string.ai_nano_ready), tone = StatusTone.Positive)
        NanoStatus.DOWNLOADABLE ->
            Button(onClick = onDownloadNano) {
                Text(stringResource(R.string.ai_nano_enable))
            }
        NanoStatus.DOWNLOADING ->
            Text(stringResource(R.string.ai_nano_downloading), style = MaterialTheme.typography.bodySmall)
        NanoStatus.UNAVAILABLE ->
            Text(
                stringResource(R.string.ai_nano_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

@Composable
private fun PreferChip(
    labelRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(labelRes)) },
    )
}

@Composable
private fun TestResult(test: ConnectionTest) {
    when (test) {
        ConnectionTest.Idle -> Unit
        ConnectionTest.Testing ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                Text(
                    stringResource(R.string.ai_provider_testing),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        ConnectionTest.Success ->
            StatusChip(stringResource(R.string.ai_provider_test_ok), tone = StatusTone.Positive)
        ConnectionTest.AuthFailed ->
            StatusChip(stringResource(R.string.ai_provider_test_auth), tone = StatusTone.Error)
        ConnectionTest.Offline ->
            StatusChip(stringResource(R.string.ai_provider_test_offline), tone = StatusTone.Neutral)
        ConnectionTest.Error ->
            StatusChip(stringResource(R.string.ai_provider_test_error), tone = StatusTone.Error)
    }
}

/**
 * Usable = any on-device model installed (Gemma or Qwen light) or Nano available. Display-only
 * mirror — the resolution authority is `OnDeviceProvider.isReady()` (same any-engine semantics).
 */
private val OnDeviceInfo.isUsable: Boolean
    get() =
        gemmaState is ModelState.Ready ||
            lightState is ModelState.Ready ||
            nanoStatus == NanoStatus.AVAILABLE

@Composable
private fun tierLabel(tier: InferenceTier): String =
    stringResource(
        when (tier) {
            InferenceTier.NANO -> R.string.ai_tier_nano
            InferenceTier.GEMMA -> R.string.ai_tier_gemma
            InferenceTier.LIGHT -> R.string.ai_tier_light
            InferenceTier.CLOUD -> R.string.ai_tier_cloud
            InferenceTier.NONE -> R.string.ai_tier_none
        },
    )

@Composable
private fun providerName(id: ProviderId): String =
    when (id) {
        ProviderId.CLAUDE -> stringResource(R.string.ai_provider_claude)
        ProviderId.GEMINI -> stringResource(R.string.ai_provider_gemini)
        ProviderId.ON_DEVICE -> stringResource(R.string.ai_provider_on_device)
        // Not chat providers (never rendered as a provider card); the arms exist for exhaustiveness.
        ProviderId.SEMANTIC_SCHOLAR -> stringResource(R.string.ai_provider_semantic_scholar)
        ProviderId.OPENALEX -> stringResource(R.string.ai_provider_openalex)
    }

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AiProviderSettingsContentPreview() {
    ArxiverTheme {
        AiProviderSettingsContent(
            state =
                AiProviderSettingsUiState(
                    rows =
                        listOf(
                            ProviderRow(ProviderId.CLAUDE, configured = true, test = ConnectionTest.Success),
                            ProviderRow(ProviderId.GEMINI, configured = false),
                            ProviderRow(
                                ProviderId.ON_DEVICE,
                                configured = true,
                                onDevice =
                                    OnDeviceInfo(
                                        recommendedTier = InferenceTier.CLOUD,
                                        totalRamMb = 7900,
                                        gemmaEligible = true,
                                        gemmaState = ModelState.NotDownloaded,
                                        lightEligible = true,
                                        lightState = ModelState.NotDownloaded,
                                        nanoStatus = NanoStatus.UNAVAILABLE,
                                    ),
                            ),
                        ),
                    selectedDefault = ProviderId.CLAUDE,
                ),
            onSaveKey = { _, _ -> },
            onClearKey = {},
            onTestConnection = { _, _ -> },
            onSelectDefault = {},
            onDownloadModel = {},
            onDeleteModel = {},
            onDownloadNano = {},
            onSetPreferredTier = {},
            onSetPreferOnDeviceWhenReady = {},
            onSaveS2Key = {},
            onClearS2Key = {},
        )
    }
}
