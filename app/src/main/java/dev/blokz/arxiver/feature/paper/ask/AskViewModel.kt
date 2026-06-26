package dev.blokz.arxiver.feature.paper.ask

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.chat.ChatMode
import dev.blokz.arxiver.chat.ChatPreview
import dev.blokz.arxiver.chat.extractFollowUps
import dev.blokz.arxiver.core.ai.AiException
import dev.blokz.arxiver.core.ai.ChatChunk
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.database.entity.ChatMessageEntity
import dev.blokz.arxiver.core.search.RelationGraphBuilder
import dev.blokz.arxiver.core.search.RetrievalScope
import dev.blokz.arxiver.data.ChatPrepareResult
import dev.blokz.arxiver.data.ChatRepository
import dev.blokz.arxiver.data.Citation
import dev.blokz.arxiver.data.GraphResult
import dev.blokz.arxiver.data.PageImageSource
import dev.blokz.arxiver.data.PreparedChat
import dev.blokz.arxiver.data.RelationGraphSource
import dev.blokz.arxiver.data.settleStructured
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
    /** Sources cited as `[1..n]` in this (assistant) answer, for tappable citations. */
    val citations: List<Citation> = emptyList(),
    /** Suggested follow-up questions (P-Rich R3b.2); live-turn only, not persisted. */
    val followUps: List<String> = emptyList(),
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
    /** Answer-depth dial (P-Rich R3b); applies to the next send or preset. */
    val mode: ChatMode = ChatMode.STANDARD,
    /** R3d.4: true only when the resolved provider is vision-capable AND a local PDF exists (paper scope). */
    val visionAvailable: Boolean = false,
    /** R3d.4: page count of the local PDF (0 = none/unknown); bounds the 1-based page picker. */
    val pageCount: Int = 0,
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
        private val pageImageSource: PageImageSource,
        private val relationGraphSource: RelationGraphSource,
    ) : ViewModel() {
        private lateinit var scope: RetrievalScope
        private var sessionId: Long? = null
        private var streamJob: Job? = null
        private var indexJob: Job? = null
        private var pendingPrepared: PreparedChat? = null

        // The mode pinned for the parked cloud request — read at ask() time, NOT from the live
        // ui state (which can change during the confirm dialog), so a Max turn keeps Max follow-ups.
        private var pendingMode: ChatMode = ChatMode.STANDARD

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
            indexJob =
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
            // R3d.4: gate the vision preset for a paper scope. Both probes are network-free
            // (pageCountIfLocal globs filesDir/pdfs; resolveVisionCapable is local store reads),
            // so they never trip the arXiv limiter and run in parallel with indexing. Collection
            // scope is left untouched (visionAvailable=false, pageCount=0).
            (scope as? RetrievalScope.Paper)?.let { paper ->
                viewModelScope.launch {
                    val pages = runCatching { pageImageSource.pageCountIfLocal(paper.paperId) }.getOrNull() ?: 0
                    val visionProvider = runCatching { chatRepository.resolveVisionCapable() }.getOrDefault(false)
                    _uiState.update { it.copy(pageCount = pages, visionAvailable = visionProvider && pages > 0) }
                }
            }
        }

        fun setInput(text: String) = _uiState.update { it.copy(input = text) }

        fun setIncludeNotes(value: Boolean) = _uiState.update { it.copy(includeNotes = value) }

        fun setMode(mode: ChatMode) = _uiState.update { it.copy(mode = mode) }

        fun send() {
            val question = _uiState.value.input.trim()
            if (question.isEmpty() || _uiState.value.streaming || _uiState.value.preparing) return
            _uiState.update { it.copy(input = "") }
            ask(question)
        }

        /** Run a one-tap preset (P-Rich R3c) — its instruction is sent as the question. */
        fun runPreset(instruction: String) {
            if (_uiState.value.streaming || _uiState.value.preparing) return
            ask(instruction)
        }

        fun summarize() = runPreset(SUMMARIZE_PROMPT)

        /**
         * Draw an app-composed relation graph for the paper (P-Atlas PA.1) — citation + similarity
         * structure from local data, with **no provider call**. The Mermaid is built deterministically
         * (valid by construction) and persisted as a complete assistant turn via
         * [ChatRepository.insertArtifactTurn]; sparse data surfaces a specific empty-state message
         * instead. Paper scope only (collection → PA.5). [question] is shown as the user turn.
         */
        fun runGraphArtifact(question: String) {
            if (_uiState.value.streaming || _uiState.value.preparing) return
            val paper = scope as? RetrievalScope.Paper ?: return
            _uiState.update { it.copy(error = null, notConfigured = false, preparing = true) }
            viewModelScope.launch {
                indexJob?.join()
                when (val result = relationGraphSource.graphForPaper(paper.paperId)) {
                    is GraphResult.Ready -> {
                        val mermaid = RelationGraphBuilder.toMermaid(result.graph)
                        if (mermaid == null) {
                            _uiState.update { it.copy(preparing = false, error = R.string.ask_graph_no_relations) }
                            return@launch
                        }
                        sessionId = chatRepository.insertArtifactTurn(scope, sessionId, question, mermaid)
                        _uiState.update {
                            it.copy(
                                preparing = false,
                                messages =
                                    it.messages +
                                        AskMessage(AskRole.USER, question) +
                                        AskMessage(AskRole.ASSISTANT, mermaid, streaming = false),
                            )
                        }
                    }
                    GraphResult.NotEmbedded ->
                        _uiState.update { it.copy(preparing = false, error = R.string.ask_graph_not_embedded) }
                    GraphResult.NoRelations ->
                        _uiState.update { it.copy(preparing = false, error = R.string.ask_graph_no_relations) }
                }
            }
        }

        fun confirmSend() {
            val prepared = pendingPrepared ?: return
            pendingPrepared = null
            startStream(prepared, pendingMode)
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
                // Wait for on-open scope indexing so retrieval sees the freshly-embedded
                // chunks; an un-embedded inbox paper would otherwise retrieve nothing.
                indexJob?.join()
                val mode = _uiState.value.mode
                val result =
                    chatRepository.prepare(scope, sessionId, question, _uiState.value.includeNotes, mode)
                handlePrepared(result, mode)
            }
        }

        /**
         * R3d.4 "Summarize with figures": attach a PDF **page image** to a grounded turn. [pageNumber]
         * is 1-based from the picker, converted to 0-based **exactly once** here (m1). Paper scope only
         * (m2). The image render is network-free (an already-downloaded page). The turn then re-enters
         * the SAME prepare → Ready → (cloud confirm | stream) path as [ask] via [handlePrepared], so a
         * cloud vision turn always parks on the "what leaves the device" confirm before sending.
         */
        fun runVisionPreset(
            instruction: String,
            pageNumber: Int,
        ) {
            if (_uiState.value.streaming || _uiState.value.preparing) return
            val paper = scope as? RetrievalScope.Paper ?: return // m2
            val pageIndex0 = (pageNumber - 1).coerceAtLeast(0) // m1: the only 0-based conversion
            _uiState.update { it.copy(error = null, notConfigured = false, preparing = true) }
            viewModelScope.launch {
                indexJob?.join()
                // Action-time re-check (the sheet-open gate can go stale: an on-device engine may
                // finish loading mid-sheet and, with prefer-on-device on, win at prepare time — the
                // assembler would then silently drop the image). Surface it + hide the chip instead.
                if (!chatRepository.resolveVisionCapable()) {
                    _uiState.update {
                        it.copy(preparing = false, visionAvailable = false, error = R.string.ask_error_no_vision)
                    }
                    return@launch
                }
                val image = pageImageSource.pageImage(paper.paperId, pageIndex0)
                if (image == null) {
                    _uiState.update { it.copy(preparing = false, error = R.string.ask_error_page_image) }
                    return@launch
                }
                val mode = _uiState.value.mode
                val result =
                    chatRepository.prepare(
                        scope,
                        sessionId,
                        instruction,
                        _uiState.value.includeNotes,
                        mode,
                        attachment = image,
                    )
                handlePrepared(result, mode)
            }
        }

        /**
         * Shared Ready / NotConfigured / Failed handling for [ask] and [runVisionPreset]. Non-suspend;
         * only touches `_uiState` + the `pending*` fields, so it is safe to call after a suspend prepare().
         */
        private fun handlePrepared(
            result: ChatPrepareResult,
            mode: ChatMode,
        ) {
            when (result) {
                is ChatPrepareResult.Ready -> {
                    val prepared = result.prepared
                    _uiState.update {
                        it.copy(preparing = false, provider = prepared.providerId, isCloud = prepared.isCloud)
                    }
                    if (prepared.isCloud) {
                        pendingPrepared = prepared
                        pendingMode = mode
                        _uiState.update { it.copy(pendingConfirm = prepared.preview) }
                    } else {
                        startStream(prepared, mode)
                    }
                }
                ChatPrepareResult.NotConfigured ->
                    _uiState.update { it.copy(preparing = false, notConfigured = true) }
                is ChatPrepareResult.Failed ->
                    _uiState.update { it.copy(preparing = false, error = result.error.toMessageRes()) }
            }
        }

        private fun startStream(
            prepared: PreparedChat,
            mode: ChatMode,
        ) {
            _uiState.update {
                it.copy(
                    pendingConfirm = null,
                    streaming = true,
                    messages =
                        it.messages +
                            AskMessage(AskRole.USER, prepared.question) +
                            AskMessage(AskRole.ASSISTANT, "", streaming = true, citations = prepared.citations),
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
                                        val (body, followUps) = settleFollowUps(prepared, mode, answer.toString())
                                        updateAssistant(body, streaming = false, followUps = followUps)
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

        /**
         * Settle a finished answer's follow-ups (P-Rich R3b.2): strip the cloud-Max `FOLLOWUPS::`
         * sentinel from the body, and use the model's suggestions for cloud-Max, else (incl.
         * on-device Max or a Max answer with no parseable block) the local heuristic generator.
         * Returns the cleaned body + the chosen follow-ups.
         */
        private fun settleFollowUps(
            prepared: PreparedChat,
            mode: ChatMode,
            rawAnswer: String,
        ): Pair<String, List<String>> {
            val (stripped, modelFollowUps) = extractFollowUps(rawAnswer)
            // PA.4: on a STRUCTURED turn, render the TABLE:: intermediate to a valid table/list — the
            // same transform the repository persists, so display == DB. Heuristics run on the result.
            val body = settleStructured(prepared, stripped)
            val fromModel = if (mode == ChatMode.MAX && prepared.isCloud) modelFollowUps else emptyList()
            val followUps =
                fromModel.ifEmpty {
                    FollowUpHeuristics.followUps(prepared.question, body, prepared.citations, priorQuestions())
                }
            return body to followUps
        }

        /** Earlier user questions this session (for follow-up dedup); excludes the live turn's own. */
        private fun priorQuestions(): List<String> =
            _uiState.value.messages.filter { it.role == AskRole.USER }.map { it.text }

        /** Replace the trailing assistant bubble (the live/just-finished turn). */
        private fun updateAssistant(
            text: String,
            streaming: Boolean,
            error: Boolean = false,
            followUps: List<String> = emptyList(),
        ) = _uiState.update { state ->
            val messages = state.messages
            val lastIndex = messages.indexOfLast { it.role == AskRole.ASSISTANT }
            if (lastIndex < 0) {
                state
            } else {
                state.copy(
                    messages =
                        messages.toMutableList().also {
                            it[lastIndex] =
                                it[lastIndex].copy(
                                    text = text,
                                    streaming = streaming,
                                    error = error,
                                    followUps = followUps,
                                )
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
