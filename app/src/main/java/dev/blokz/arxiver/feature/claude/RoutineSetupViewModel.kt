package dev.blokz.arxiver.feature.claude

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.claude.RoutineTemplate
import dev.blokz.arxiver.core.claude.RoutineTemplateCatalog
import dev.blokz.arxiver.data.RoutineSetupGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SetupStep { CREATE_ROUTINE, CONNECT, VERIFY }

/** SPEC-CLAUDE-BRIDGE §8.1: verification is opt-in and runs after save. */
sealed interface VerificationState {
    data object Idle : VerificationState

    data object Sending : VerificationState

    data object Verified : VerificationState

    /** Ping queued offline — auto-sends later; safe to finish setup. */
    data object SkippedOffline : VerificationState

    data class Failed(val error: VerificationError) : VerificationState
}

data class RoutineSetupUiState(
    val template: RoutineTemplate? = null,
    val step: SetupStep = SetupStep.CREATE_ROUTINE,
    val name: String = "",
    val url: String = "",
    val token: String = "",
    val saving: Boolean = false,
    val savedRoutineId: Long? = null,
    val verification: VerificationState = VerificationState.Idle,
) {
    val urlHttpsValid: Boolean get() = url.startsWith("https://")

    /** Soft signal only — matches `normalizeTriggerUrl`'s expected shape. */
    val urlLooksRoutineShaped: Boolean get() = "/routines/" in url

    val connectInputValid: Boolean get() = name.isNotBlank() && urlHttpsValid && token.isNotBlank()
}

/**
 * Guided setup wizard (SPEC-ROUTINES-CATALOG §4): create the routine on
 * claude.ai → connect URL + token → opt-in verification. The token lives only
 * in this in-memory state until it enters TokenVault via the gateway — it is
 * never logged or persisted elsewhere (red lines).
 */
@HiltViewModel
class RoutineSetupViewModel
    @Inject
    constructor(
        private val gateway: RoutineSetupGateway,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                savedStateHandle.get<String>("templateId")
                    ?.let(RoutineTemplateCatalog::byId)
                    .let { template -> RoutineSetupUiState(template = template, name = template?.name.orEmpty()) },
            )
        val uiState: StateFlow<RoutineSetupUiState> = _uiState.asStateFlow()

        fun onNameChange(value: String) = _uiState.update { it.copy(name = value) }

        fun onUrlChange(value: String) = _uiState.update { it.copy(url = value) }

        fun onTokenChange(value: String) = _uiState.update { it.copy(token = value) }

        fun continueToConnect() = _uiState.update { it.copy(step = SetupStep.CONNECT) }

        fun backStep() =
            _uiState.update {
                when (it.step) {
                    SetupStep.CREATE_ROUTINE -> it
                    SetupStep.CONNECT -> it.copy(step = SetupStep.CREATE_ROUTINE)
                    SetupStep.VERIFY -> it.copy(step = SetupStep.CONNECT, verification = VerificationState.Idle)
                }
            }

        /**
         * Persists the routine, then advances to verify — save-before-verify
         * per SPEC-CLAUDE-BRIDGE §8.1, so a failed ping never loses input.
         * Returning here after edits re-saves the same routine.
         */
        fun saveAndContinue() {
            val state = _uiState.value
            if (!state.connectInputValid || state.saving) return
            viewModelScope.launch {
                _uiState.update { it.copy(saving = true) }
                val routineId =
                    state.savedRoutineId?.also { id ->
                        gateway.updateRoutine(id, state.name, state.url)
                        gateway.replaceToken(id, state.token)
                    } ?: gateway.addRoutine(state.name, state.url, state.token)
                _uiState.update {
                    it.copy(
                        saving = false,
                        savedRoutineId = routineId,
                        step = SetupStep.VERIFY,
                        verification = VerificationState.Idle,
                    )
                }
            }
        }

        /** Opt-in test ping — a real run; the UI's consent copy says so. */
        fun verify() {
            val routineId = _uiState.value.savedRoutineId ?: return
            if (_uiState.value.verification == VerificationState.Sending) return
            viewModelScope.launch {
                _uiState.update { it.copy(verification = VerificationState.Sending) }
                val verification =
                    when (val error = VerificationError.from(gateway.ping(routineId))) {
                        null -> VerificationState.Verified
                        VerificationError.Offline -> VerificationState.SkippedOffline
                        else -> VerificationState.Failed(error)
                    }
                _uiState.update { it.copy(verification = verification) }
            }
        }
    }
