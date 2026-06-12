package dev.blokz.arxiver.feature.claude

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.claude.RoutineStarterInstructions
import dev.blokz.arxiver.core.database.entity.RoutineConfigEntity
import dev.blokz.arxiver.data.DispatchRepository
import dev.blokz.arxiver.data.DispatchSubmission
import dev.blokz.arxiver.ui.components.StatusChip
import dev.blokz.arxiver.ui.components.StatusTone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RoutinesViewModel
    @Inject
    constructor(
        private val dispatchRepository: DispatchRepository,
    ) : ViewModel() {
        val routines: StateFlow<List<RoutineConfigEntity>> =
            dispatchRepository.observeRoutines()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        private val _pingResult = MutableStateFlow<String?>(null)
        val pingResult: StateFlow<String?> = _pingResult.asStateFlow()

        fun add(
            name: String,
            url: String,
            token: String,
        ) {
            viewModelScope.launch { dispatchRepository.addRoutine(name, url, token) }
        }

        fun replaceToken(
            routineId: Long,
            token: String,
        ) {
            viewModelScope.launch { dispatchRepository.replaceToken(routineId, token) }
        }

        fun delete(routineId: Long) {
            viewModelScope.launch { dispatchRepository.deleteRoutine(routineId) }
        }

        /** SPEC-CLAUDE-BRIDGE §2: optional test ping — triggers a real routine run. */
        fun ping(routineId: Long) {
            viewModelScope.launch {
                _pingResult.value =
                    when (val result = dispatchRepository.ping(routineId)) {
                        is DispatchSubmission.Sent -> "ping_ok"
                        is DispatchSubmission.AuthRejected -> "ping_auth"
                        is DispatchSubmission.Queued -> "ping_queued"
                        is DispatchSubmission.Failed -> "ping_failed:${result.reason}"
                        else -> "ping_failed:unexpected"
                    }
            }
        }

        fun consumePingResult() {
            _pingResult.value = null
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(
    onBack: () -> Unit,
    onOpenTemplates: () -> Unit,
    viewModel: RoutinesViewModel = hiltViewModel(),
) {
    val routines by viewModel.routines.collectAsState()
    val pingResult by viewModel.pingResult.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var reauthRoutine by remember { mutableStateOf<RoutineConfigEntity?>(null) }
    var pingCandidate by remember { mutableStateOf<RoutineConfigEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val pingMessages =
        mapOf(
            "ping_ok" to stringResource(R.string.routine_ping_ok),
            "ping_auth" to stringResource(R.string.routine_ping_auth),
            "ping_queued" to stringResource(R.string.routine_ping_queued),
        )
    pingResult?.let { result ->
        val message =
            pingMessages[result]
                ?: stringResource(R.string.routine_ping_failed, result.removePrefix("ping_failed:"))
        scope.launch {
            snackbar.showSnackbar(message)
            viewModel.consumePingResult()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.routines_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            copyStarterInstructions(context)
                            scope.launch {
                                snackbar.showSnackbar(context.getString(R.string.routine_instructions_copied))
                            }
                        },
                    ) {
                        Icon(Icons.Filled.ContentCopy, stringResource(R.string.cd_copy_starter))
                    }
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Filled.Add, stringResource(R.string.cd_add_routine))
                    }
                },
            )
        },
    ) { padding ->
        if (routines.isEmpty()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.routines_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.routines_empty_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                FilledTonalButton(
                    onClick = onOpenTemplates,
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(stringResource(R.string.template_browse_action))
                }
            }
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
            ) {
                items(routines, key = { it.id }) { routine ->
                    RoutineRow(
                        routine = routine,
                        onPing = { pingCandidate = routine },
                        onReauth = { reauthRoutine = routine },
                        onDelete = { viewModel.delete(routine.id) },
                    )
                }
            }
        }
    }

    pingCandidate?.let { routine ->
        AlertDialog(
            onDismissRequest = { pingCandidate = null },
            title = { Text(stringResource(R.string.routine_ping_confirm_title)) },
            text = { Text(stringResource(R.string.routine_ping_confirm_body, routine.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.ping(routine.id)
                        pingCandidate = null
                    },
                ) { Text(stringResource(R.string.routine_ping_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pingCandidate = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showAdd) {
        RoutineDialog(
            title = stringResource(R.string.routine_add_title),
            initialName = "",
            initialUrl = "",
            askNameUrl = true,
            onConfirm = { name, url, token ->
                viewModel.add(name, url, token)
                showAdd = false
            },
            onDismiss = { showAdd = false },
        )
    }
    reauthRoutine?.let { routine ->
        RoutineDialog(
            title = stringResource(R.string.routine_reauth_title, routine.name),
            initialName = routine.name,
            initialUrl = routine.triggerUrl,
            askNameUrl = false,
            onConfirm = { _, _, token ->
                viewModel.replaceToken(routine.id, token)
                reauthRoutine = null
            },
            onDismiss = { reauthRoutine = null },
        )
    }
}

private fun copyStarterInstructions(context: Context) {
    copyInstructionsToClipboard(context, RoutineStarterInstructions.generate())
}

@Composable
private fun RoutineRow(
    routine: RoutineConfigEntity,
    onPing: () -> Unit,
    onReauth: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(routine.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = routine.triggerUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (routine.authInvalid) {
                StatusChip(
                    text = stringResource(R.string.routine_auth_invalid),
                    tone = StatusTone.Error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        TextButton(onClick = onPing) { Text(stringResource(R.string.routine_ping)) }
        TextButton(onClick = onReauth) { Text(stringResource(R.string.routine_reauth)) }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, stringResource(R.string.cd_delete_routine, routine.name))
        }
    }
}

@Composable
private fun RoutineDialog(
    title: String,
    initialName: String,
    initialUrl: String,
    askNameUrl: Boolean,
    onConfirm: (name: String, url: String, token: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf("") }
    val urlValid = url.startsWith("https://")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (askNameUrl) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.routine_name_label)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.routine_url_label)) },
                        singleLine = true,
                        isError = url.isNotEmpty() && !urlValid,
                        supportingText = {
                            if (url.isNotEmpty() && !urlValid) {
                                Text(stringResource(R.string.routine_url_https_required))
                            }
                        },
                    )
                }
                // Tokens are write-only (SPEC-CLAUDE-BRIDGE §2): never shown back.
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.routine_token_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, url, token) },
                enabled = token.isNotBlank() && (!askNameUrl || (name.isNotBlank() && urlValid)),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
