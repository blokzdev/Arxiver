package dev.blokz.arxiver.feature.claude

import android.content.Intent
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.claude.RoutineStarterInstructions
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import kotlinx.coroutines.launch

private const val CLAUDE_ROUTINES_URL = "https://claude.ai/code/routines"

/**
 * Guided setup wizard (SPEC-ROUTINES-CATALOG §4): one route, three internal
 * steps. Step 3's verification follows SPEC-CLAUDE-BRIDGE §8 — opt-in,
 * consented, after the routine is already saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineSetupScreen(
    onExit: () -> Unit,
    onDone: () -> Unit,
    viewModel: RoutineSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.step == SetupStep.CREATE_ROUTINE) onExit() else viewModel.backStep()
                        },
                    ) {
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
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val stepNumber = state.step.ordinal + 1
            Text(
                stringResource(R.string.setup_step_indicator, stepNumber),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { stepNumber / 3f },
                modifier = Modifier.fillMaxWidth(),
            )
            when (state.step) {
                SetupStep.CREATE_ROUTINE ->
                    CreateRoutineStep(
                        state = state,
                        snackbar = snackbar,
                        onContinue = viewModel::continueToConnect,
                    )
                SetupStep.CONNECT ->
                    ConnectStep(
                        state = state,
                        onNameChange = viewModel::onNameChange,
                        onUrlChange = viewModel::onUrlChange,
                        onTokenChange = viewModel::onTokenChange,
                        onSave = viewModel::saveAndContinue,
                    )
                SetupStep.VERIFY ->
                    VerifyStep(
                        state = state,
                        onVerify = viewModel::verify,
                        onEdit = viewModel::backStep,
                        onDone = onDone,
                    )
            }
        }
    }
}

@Composable
private fun CreateRoutineStep(
    state: RoutineSetupUiState,
    snackbar: SnackbarHostState,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Text(stringResource(R.string.setup_create_title), style = MaterialTheme.typography.titleMedium)
    state.template?.let { template ->
        Text(template.purpose, style = MaterialTheme.typography.bodyMedium)
    }
    listOf(
        stringResource(R.string.setup_create_step1),
        stringResource(R.string.setup_create_step2),
        stringResource(R.string.setup_create_step3),
        state.template?.let { template ->
            if (template.connectors.isEmpty()) {
                stringResource(R.string.setup_create_step4_none)
            } else {
                stringResource(R.string.setup_create_step4_connectors, template.connectors.joinToString())
            }
        } ?: stringResource(R.string.setup_create_step4_none),
    ).forEachIndexed { index, line ->
        Text(
            "${index + 1}. $line",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            onClick = {
                val instructions =
                    state.template
                        ?.let(RoutineStarterInstructions::generateFor)
                        ?: RoutineStarterInstructions.generate()
                copyInstructionsToClipboard(context, instructions)
                scope.launch {
                    snackbar.showSnackbar(context.getString(R.string.routine_instructions_copied))
                }
            },
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null)
            Text(
                stringResource(R.string.template_copy_instructions),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        TextButton(
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, CLAUDE_ROUTINES_URL.toUri()))
            },
        ) { Text(stringResource(R.string.setup_open_claude)) }
    }
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_continue))
    }
}

@Composable
private fun ConnectStep(
    state: RoutineSetupUiState,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Text(stringResource(R.string.setup_connect_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.setup_connect_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = state.name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.routine_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.url,
        onValueChange = onUrlChange,
        label = { Text(stringResource(R.string.routine_url_label)) },
        singleLine = true,
        isError = state.url.isNotEmpty() && !state.urlHttpsValid,
        supportingText = {
            when {
                state.url.isNotEmpty() && !state.urlHttpsValid ->
                    Text(stringResource(R.string.routine_url_https_required))
                state.urlHttpsValid && !state.urlLooksRoutineShaped ->
                    Text(stringResource(R.string.setup_url_shape_warning))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    // Tokens are write-only (SPEC-CLAUDE-BRIDGE §2): masked, never shown back.
    OutlinedTextField(
        value = state.token,
        onValueChange = onTokenChange,
        label = { Text(stringResource(R.string.routine_token_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = onSave,
        enabled = state.connectInputValid && !state.saving,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.setup_save_continue))
    }
}

@Composable
private fun VerifyStep(
    state: RoutineSetupUiState,
    onVerify: () -> Unit,
    onEdit: () -> Unit,
    onDone: () -> Unit,
) {
    Text(stringResource(R.string.setup_verify_title), style = MaterialTheme.typography.titleMedium)
    when (val verification = state.verification) {
        VerificationState.Idle -> {
            Text(
                stringResource(R.string.setup_verify_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onVerify, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_send_test))
            }
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_skip_test))
            }
        }
        VerificationState.Sending -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.setup_verifying), style = MaterialTheme.typography.bodyMedium)
            }
        }
        VerificationState.Verified -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.setup_verified_title), style = MaterialTheme.typography.titleSmall)
            }
            Text(
                stringResource(R.string.setup_verified_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_done))
            }
        }
        VerificationState.SkippedOffline -> {
            Text(
                stringResource(R.string.setup_queued_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_done))
            }
        }
        is VerificationState.Failed -> {
            Text(
                stringResource(R.string.setup_failed_title, verification.reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onVerify, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_retry))
            }
            TextButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_edit_connection))
            }
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_keep_anyway))
            }
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ConnectStepPreview() {
    ArxiverTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectStep(
                state =
                    RoutineSetupUiState(
                        step = SetupStep.CONNECT,
                        name = "Paper Digest",
                        url = "https://api.anthropic.com/v1/claude_code/routines/example",
                    ),
                onNameChange = {},
                onUrlChange = {},
                onTokenChange = {},
                onSave = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun VerifyStepPreview() {
    ArxiverTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VerifyStep(
                state = RoutineSetupUiState(step = SetupStep.VERIFY, savedRoutineId = 1),
                onVerify = {},
                onEdit = {},
                onDone = {},
            )
        }
    }
}
