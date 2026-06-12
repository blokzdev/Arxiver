package dev.blokz.arxiver.feature.claude

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.claude.PayloadBuilder
import dev.blokz.arxiver.core.claude.PayloadResult
import dev.blokz.arxiver.core.claude.RoutineAction
import dev.blokz.arxiver.core.database.entity.RoutineConfigEntity
import dev.blokz.arxiver.data.DispatchRepository
import dev.blokz.arxiver.data.DispatchSubmission
import dev.blokz.arxiver.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DispatchUiState(
    val paperIds: List<String> = emptyList(),
    val routines: List<RoutineConfigEntity> = emptyList(),
    val selectedRoutineId: Long? = null,
    val availableActions: List<RoutineAction> = emptyList(),
    val action: RoutineAction = RoutineAction.DIGEST,
    val instruction: String = "",
    val includeNotes: Boolean = true,
    val previewExpanded: Boolean = false,
    val previewJson: String? = null,
    val sending: Boolean = false,
    val error: String? = null,
    /** Non-null when the dispatch finished (terminal state for the sheet). */
    val completed: DispatchSubmission? = null,
)

@HiltViewModel
class DispatchViewModel
    @Inject
    constructor(
        private val dispatchRepository: DispatchRepository,
        private val syncScheduler: SyncScheduler,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(DispatchUiState())
        val uiState: StateFlow<DispatchUiState> = _uiState.asStateFlow()

        fun start(
            paperIds: List<String>,
            presetAction: RoutineAction? = null,
        ) {
            if (_uiState.value.paperIds == paperIds && _uiState.value.routines.isNotEmpty()) return
            val actions = actionsFor(paperIds.size, presetAction)
            val initial = presetAction ?: actions.first()
            _uiState.update {
                DispatchUiState(
                    paperIds = paperIds,
                    availableActions = actions,
                    action = initial,
                    instruction = PayloadBuilder.defaultInstruction(initial),
                )
            }
            viewModelScope.launch {
                dispatchRepository.observeRoutines().collect { routines ->
                    _uiState.update { state ->
                        state.copy(
                            routines = routines,
                            selectedRoutineId = state.selectedRoutineId ?: routines.firstOrNull()?.id,
                        )
                    }
                }
            }
        }

        /** SPEC-CLAUDE-BRIDGE §5: action availability follows selection size. */
        private fun actionsFor(
            count: Int,
            presetAction: RoutineAction? = null,
        ): List<RoutineAction> =
            buildList {
                presetAction?.let(::add)
                when {
                    count == 1 -> {
                        add(RoutineAction.DIGEST)
                        add(RoutineAction.DEEP_DIVE)
                    }
                    count in 2..6 -> {
                        add(RoutineAction.DIGEST)
                        add(RoutineAction.COMPARE)
                    }
                    else -> add(RoutineAction.DIGEST)
                }
                add(RoutineAction.LITERATURE_SCAN)
                add(RoutineAction.CUSTOM)
            }.distinct()

        fun selectRoutine(id: Long) = _uiState.update { it.copy(selectedRoutineId = id) }

        fun selectAction(action: RoutineAction) {
            _uiState.update {
                it.copy(
                    action = action,
                    instruction = PayloadBuilder.defaultInstruction(action),
                    previewJson = null,
                )
            }
            refreshPreviewIfVisible()
        }

        fun setInstruction(text: String) {
            _uiState.update { it.copy(instruction = text, previewJson = null) }
        }

        fun setIncludeNotes(include: Boolean) {
            _uiState.update { it.copy(includeNotes = include, previewJson = null) }
            refreshPreviewIfVisible()
        }

        fun togglePreview() {
            _uiState.update { it.copy(previewExpanded = !it.previewExpanded) }
            refreshPreviewIfVisible()
        }

        private fun refreshPreviewIfVisible() {
            val state = _uiState.value
            if (!state.previewExpanded || state.previewJson != null) return
            viewModelScope.launch {
                val result =
                    dispatchRepository.previewPayload(
                        action = state.action,
                        instruction = state.instruction,
                        paperIds = state.paperIds,
                        includeNotes = state.includeNotes,
                    )
                _uiState.update {
                    it.copy(
                        previewJson =
                            when (result) {
                                is PayloadResult.Ready ->
                                    dev.blokz.arxiver.core.claude.DispatchEnvelope.render(prettify(result.json))
                                is PayloadResult.TooLarge -> "payload too large: ${result.byteSize} bytes"
                            },
                    )
                }
            }
        }

        fun send() {
            val state = _uiState.value
            val routineId = state.selectedRoutineId ?: return
            _uiState.update { it.copy(sending = true, error = null) }
            viewModelScope.launch {
                val submission =
                    dispatchRepository.dispatch(
                        routineId = routineId,
                        action = state.action,
                        instruction = state.instruction,
                        paperIds = state.paperIds,
                        includeNotes = state.includeNotes,
                    )
                when (submission) {
                    is DispatchSubmission.Sent, is DispatchSubmission.Queued -> {
                        if (submission is DispatchSubmission.Queued) syncScheduler.drainDispatches()
                        _uiState.update { it.copy(sending = false, completed = submission) }
                    }
                    is DispatchSubmission.AuthRejected ->
                        _uiState.update {
                            it.copy(
                                sending = false,
                                error = "Routine token rejected — re-enter it in Routines settings.",
                            )
                        }
                    is DispatchSubmission.Failed ->
                        _uiState.update {
                            it.copy(sending = false, error = "Dispatch failed: ${submission.reason}")
                        }
                    is DispatchSubmission.PayloadTooLarge ->
                        _uiState.update {
                            it.copy(
                                sending = false,
                                error =
                                    "Payload too large (${submission.byteSize / 1024}KB > " +
                                        "${submission.limit / 1024}KB) — split the selection.",
                            )
                        }
                }
            }
        }

        private fun prettify(json: String): String =
            runCatching {
                val parsed = kotlinx.serialization.json.Json.parseToJsonElement(json)
                kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(
                    kotlinx.serialization.json.JsonElement.serializer(),
                    parsed,
                )
            }.getOrDefault(json)
    }
