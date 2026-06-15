package dev.blokz.arxiver.feature.settings

import android.content.res.Configuration
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.ai.ProviderId
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
        )
    }
}

@Composable
private fun AiProviderSettingsContent(
    state: AiProviderSettingsUiState,
    modifier: Modifier = Modifier,
    onSaveKey: (ProviderId, String) -> Unit,
    onClearKey: (ProviderId) -> Unit,
    onTestConnection: (ProviderId) -> Unit,
    onSelectDefault: (ProviderId) -> Unit,
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
                onTestConnection = { onTestConnection(row.id) },
                onSelectDefault = { onSelectDefault(row.id) },
            )
        }
        Text(
            stringResource(R.string.ai_providers_privacy_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = Spacing.lg),
        )
    }
}

@Composable
private fun ProviderCard(
    row: ProviderRow,
    isDefault: Boolean,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onTestConnection: () -> Unit,
    onSelectDefault: () -> Unit,
) {
    var keyInput by remember(row.id) { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                providerName(row.id),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (row.configured) {
                StatusChip(stringResource(R.string.ai_provider_connected), tone = StatusTone.Positive)
            } else {
                StatusChip(stringResource(R.string.ai_provider_not_connected), tone = StatusTone.Neutral)
            }
        }

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
            if (row.configured) {
                TextButton(onClick = onTestConnection) {
                    Text(stringResource(R.string.ai_provider_test))
                }
                TextButton(onClick = onClearKey) {
                    Text(stringResource(R.string.ai_provider_clear_key))
                }
            }
        }

        TestResult(row.test)

        if (row.configured) {
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

@Composable
private fun providerName(id: ProviderId): String =
    when (id) {
        ProviderId.CLAUDE -> stringResource(R.string.ai_provider_claude)
        ProviderId.GEMINI -> stringResource(R.string.ai_provider_gemini)
        ProviderId.ON_DEVICE -> stringResource(R.string.ai_provider_on_device)
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
                        ),
                    selectedDefault = ProviderId.CLAUDE,
                ),
            onSaveKey = { _, _ -> },
            onClearKey = {},
            onTestConnection = {},
            onSelectDefault = {},
        )
    }
}
