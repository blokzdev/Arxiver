package dev.blokz.arxiver.feature.paper.ask

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.chat.ChatPreview
import dev.blokz.arxiver.core.ai.AiException
import dev.blokz.arxiver.core.ai.ChatChunk
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.database.entity.ChatMessageEntity
import dev.blokz.arxiver.core.search.RetrievalScope
import dev.blokz.arxiver.data.ChatPrepareResult
import dev.blokz.arxiver.data.ChatRepository
import dev.blokz.arxiver.data.PreparedChat
import dev.blokz.arxiver.rag.ScopeIndexer
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class AskRole { USER, ASSISTANT }

/** One rendered turn. [streaming] marks the live assistant bubble; [error] a failed one. */
data class AskMessage(
    val role: AskRole,
    val text: String,
    val streaming: Boolean = false,
    val error: Boolean = false,
)

data class AskUiState(
    val messages: List<AskMessage> = emptyList(),
    val input: String = "",
    val includeNotes: Boolean = true,
    /** True while the scope's papers are being chunk-embedded on open (collections). */
    val indexing: Boolean = false,
    val preparing: Boolean = false,
    val streaming: Boolean = false,
    /** Non-null while a cloud call awaits the "what leaves the device" confirm. */
    val pendingConfirm: ChatPreview? = null,
    val provider: ProviderId? = null,
    val isCloud: Boolean = false,
    val notConfigured: Boolean = false,
    @StringRes val error: Int? = null,
)

/**
 * Drives the per-paper "Ask" sheet (P2.3) over [ChatRepository]. `prepare` is
 * read-only, so a cloud turn pauses on [AskUiState.pendingConfirm] until the user
 * confirms the "what leaves the device" preview; on-device streams immediately.
 * The in-memory [AskUiState.messages] is the single render source (optimistic
 * during streaming); the DB is the durable store and hydrates the latest session
 * on open. Per-paper only — collections + a history list are P2.4.
 */
@HiltViewModel
class AskViewModel
    @Inject
    constructor(
        private val chatRepository: ChatRepository,
        private val scopeIndexer: ScopeIndexer,
    ) : ViewModel() {
        private lateinit var scope: RetrievalScope
        private var sessionId: Long? = null
        private var streamJob: Job? = null
        private var pendingPrepared: PreparedChat? = null

        private val _uiState = MutableStateFlow(AskUiState())
        val uiState: StateFlow<AskUiState> = _uiState.asStateFlow()

        /**
         * Bind the sheet to a [scope] (a paper or a collection). Hydrates [sessionId]'s
         * turns when given (history resume), else the scope's most-recent session, and
         * ensures the scope's papers are chunk-embedded in the background.
         */
        fun start(
            scope: RetrievalScope,
            sessionId: Long? = null,
        ) {
            if (::scope.isInitialized) return
            this.scope = scope
            viewModelScope.launch {
                _uiState.update { it.copy(indexing = true) }
                runCatching { scopeIndexer.ensureIndexed(scope) }
                _uiState.update { it.copy(indexing = false) }
            }
            viewModelScope.launch {
                val resume = sessionId ?: chatRepository.observeSessions(scope).first().firstOrNull()?.id
                this@AskViewModel.sessionId = resume ?: return@launch
                val history = chatRepository.observeMessages(resume).first().map { it.toAskMessage() }
                _uiState.update { it.copy(messages = history) }
            }
        }

        fun setInput(text: String) = _uiState.update { it.copy(input = text) }

        fun setIncludeNotes(value: Boolean) = _uiState.update { it.copy(includeNotes = value) }

        fun send() {
            val question = _uiState.value.input.trim()
            if (question.isEmpty() || _uiState.value.streaming || _uiState.value.preparing) return
            _uiState.update { it.copy(input = "") }
            ask(question)
        }

        fun summarize() {
            if (_uiState.value.streaming || _uiState.value.preparing) return
            ask(SUMMARIZE_PROMPT)
        }

        fun confirmSend() {
            val prepared = pendingPrepared ?: return
            pendingPrepared = null
            startStream(prepared)
        }

        fun cancelConfirm() {
            pendingPrepared = null
            _uiState.update { it.copy(pendingConfirm = null) }
        }

        /** Stop a live stream; the repository persists the partial answer as incomplete. */
        fun cancel() {
            streamJob?.cancel()
        }

        private fun ask(question: String) {
            _uiState.update { it.copy(error = null, notConfigured = false, preparing = true) }
            viewModelScope.launch {
                when (val result = chatRepository.prepare(scope, sessionId, question, _uiState.value.includeNotes)) {
                    is ChatPrepareResult.Ready -> {
                        val prepared = result.prepared
                        _uiState.update {
                            it.copy(preparing = false, provider = prepared.providerId, isCloud = prepared.isCloud)
                        }
                        if (prepared.isCloud) {
                            pendingPrepared = prepared
                            _uiState.update { it.copy(pendingConfirm = prepared.preview) }
                        } else {
                            startStream(prepared)
                        }
                    }
                    ChatPrepareResult.NotConfigured ->
                        _uiState.update { it.copy(preparing = false, notConfigured = true) }
                    is ChatPrepareResult.Failed ->
                        _uiState.update { it.copy(preparing = false, error = result.error.toMessageRes()) }
                }
            }
        }

        private fun startStream(prepared: PreparedChat) {
            _uiState.update {
                it.copy(
                    pendingConfirm = null,
                    streaming = true,
                    messages =
                        it.messages +
                            AskMessage(AskRole.USER, prepared.question) +
                            AskMessage(AskRole.ASSISTANT, "", streaming = true),
                )
            }
            streamJob =
                viewModelScope.launch {
                    val answer = StringBuilder()
                    try {
                        chatRepository.stream(prepared).collect { chunk ->
                            when (chunk) {
                                is ChatChunk.Delta -> {
                                    answer.append(chunk.text)
                                    updateAssistant(answer.toString(), streaming = true)
                                }
                                is ChatChunk.Done ->
                                    if (answer.isBlank()) {
                                        // A provider that completed without producing any text is a
                                        // failure, not a blank answer (on-device engines now throw, but
                                        // guard cloud providers too) — show the error, not an empty bubble.
                                        updateAssistant(answer.toString(), streaming = false, error = true)
                                        _uiState.update { it.copy(error = R.string.ask_error_generic) }
                                    } else {
                                        updateAssistant(answer.toString(), streaming = false)
                                    }
                            }
                        }
                    } catch (e: AiException) {
                        updateAssistant(answer.toString(), streaming = false, error = true)
                        _uiState.update { it.copy(error = e.error.toMessageRes()) }
                    } finally {
                        // Settle the turn (cancellation leaves the partial incomplete), preserving
                        // its text/error, then refresh the session id for follow-ups.
                        _uiState.update { state ->
                            val i = state.messages.indexOfLast { it.role == AskRole.ASSISTANT }
                            val messages =
                                if (i < 0) {
                                    state.messages
                                } else {
                                    state.messages.toMutableList().also { it[i] = it[i].copy(streaming = false) }
                                }
                            state.copy(streaming = false, messages = messages)
                        }
                        withContext(NonCancellable) {
                            sessionId = chatRepository.observeSessions(scope).first().firstOrNull()?.id ?: sessionId
                        }
                    }
                }
        }

        /** Replace the trailing assistant bubble (the live/just-finished turn). */
        private fun updateAssistant(
            text: String,
            streaming: Boolean,
            error: Boolean = false,
        ) = _uiState.update { state ->
            val messages = state.messages
            val lastIndex = messages.indexOfLast { it.role == AskRole.ASSISTANT }
            if (lastIndex < 0) {
                state
            } else {
                state.copy(
                    messages =
                        messages.toMutableList().also {
                            it[lastIndex] = it[lastIndex].copy(text = text, streaming = streaming, error = error)
                        },
                )
            }
        }

        private fun ChatMessageEntity.toAskMessage(): AskMessage =
            AskMessage(
                role = if (role == ChatMessageEntity.ROLE_ASSISTANT) AskRole.ASSISTANT else AskRole.USER,
                text = content,
                error = status == ChatMessageEntity.STATUS_ERROR,
            )

        @StringRes
        private fun AppError.toMessageRes(): Int =
            when (this) {
                AppError.Offline -> R.string.ask_error_offline
                AppError.RateLimited -> R.string.ask_error_rate_limited
                else -> R.string.ask_error_generic
            }

        companion object {
            const val SUMMARIZE_PROMPT =
                "Summarize this paper in 3–4 sentences for a researcher skimming it: the problem, " +
                    "the approach, and the key result."
        }
    }
