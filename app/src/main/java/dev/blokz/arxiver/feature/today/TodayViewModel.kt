package dev.blokz.arxiver.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.search.eval.RelevanceThreshold
import dev.blokz.arxiver.data.ContinueReadingUi
import dev.blokz.arxiver.data.EmergingAreaUi
import dev.blokz.arxiver.data.InboxPaper
import dev.blokz.arxiver.data.InboxRepository
import dev.blokz.arxiver.data.ReadingProgressRepository
import dev.blokz.arxiver.data.RecShelfRepository
import dev.blokz.arxiver.data.RecShelfResult
import dev.blokz.arxiver.data.TrendingRepository
import dev.blokz.arxiver.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class TodayUiState(
    val items: List<InboxPaper> = emptyList(),
    val syncing: Boolean = false,
    val hasFollows: Boolean = true,
    /**
     * The raw-score cut for "Likely relevant" (P5.3): the persisted Platt calibration's p=0.5 point when one is
     * fitted, else EXACTLY the legacy 0.55. Calibration is monotone, so this only moves the cut — never the order.
     */
    val relevantThreshold: Double = LEGACY_RELEVANT_THRESHOLD,
    /** "Emerging in your areas" (P-Discover2 PD.3b) — empty unless the opt-in is on and an area cleared the bar. */
    val emergingAreas: List<EmergingAreaUi> = emptyList(),
    /** "Continue reading" (P-Read) — papers you genuinely scrolled into + haven't finished; empty is the calm norm. */
    val continueReading: List<ContinueReadingUi> = emptyList(),
) {
    /** First load: a sync is running and nothing's arrived yet — show skeletons, not "inbox zero". */
    val loading: Boolean get() = syncing && items.isEmpty() && hasFollows
}

/**
 * The pre-P5.3 hardcoded cut — what an uncalibrated (below-floor) profile keeps, exactly. Aliases the canonical
 * [RelevanceThreshold.LEGACY_CUT] (single source of truth) so previews/tests reading it stay in lockstep.
 */
const val LEGACY_RELEVANT_THRESHOLD = RelevanceThreshold.LEGACY_CUT

/** The shelf fetch's hard ceiling — a queued 1.2s S2 mutex slot must not hang the shelf forever (PRS.3). */
private const val RECSHELF_TIMEOUT_MS = 9_000L

/**
 * The "Recommended for you" shelf (P-RecShelf PRS.3) — a SEPARATE flow from [TodayUiState] so the
 * 5-source inbox combine stays untouched. Tap-gated: egress happens only on [fetchRecommendations]
 * (an explicit tap on the disclosed invitation), and the result is memoized for the surface's life.
 */
sealed interface RecShelfUiState {
    /** No seedable positive on device — the shelf is absent (cold-start silence, not an empty card). */
    data object Hidden : RecShelfUiState

    /** The invitation card: [seedCount] is the EXACT number of ids the tap will send (the disclosure). */
    data class Idle(val seedCount: Int) : RecShelfUiState

    /** A fetch is in flight. */
    data object Loading : RecShelfUiState

    /** Terminal — the honest typed outcome (Ready rows / distinct empties / retryable error). */
    data class Done(val result: RecShelfResult) : RecShelfUiState
}

enum class TriageKind { SAVED, DISMISSED }

/** One completed swipe — carries everything undo needs to restore it. */
data class TriageEvent(
    val paperId: String,
    val kind: TriageKind,
    val previousState: String,
)

@HiltViewModel
class TodayViewModel
    @Inject
    constructor(
        private val inboxRepository: InboxRepository,
        private val syncScheduler: SyncScheduler,
        private val libraryRepository: dev.blokz.arxiver.data.LibraryRepository,
        private val recShelfRepository: RecShelfRepository,
        followsRepository: dev.blokz.arxiver.data.CategoryRepository,
        relevanceModelDao: dev.blokz.arxiver.core.database.dao.RelevanceModelDao,
        trendingRepository: TrendingRepository,
        readingProgressRepository: ReadingProgressRepository,
    ) : ViewModel() {
        // The existing 5-source combine (typed combine maxes at 5 args); the P-Read shelf is a 6th source, folded
        // in via a NESTED 2-arg combine so this base — and every existing source — stays untouched.
        private val base: kotlinx.coroutines.flow.Flow<TodayUiState> =
            combine(
                inboxRepository.observeInbox(),
                syncScheduler.observeSyncRunning(),
                // Origin-agnostic (PF.3): a user whose only follows are non-arXiv still "has follows", so Today
                // shows first-sync skeletons + a filling inbox instead of the "you follow nothing" empty state.
                followsRepository.observeEnabledFollowCount(),
                relevanceModelDao.observe(),
                // Read-only cache (PD.3b); empty when the opt-in is off — the worker computes, the UI only reads.
                trendingRepository.observeAreas(),
            ) { items, syncing, followCount, model, areas ->
                TodayUiState(
                    items = items,
                    syncing = syncing,
                    hasFollows = followCount > 0,
                    // The calibrated p=0.5 point translated ONCE to a raw-score cut, else the legacy 0.55 —
                    // resolved via the shared helper so Today, the debug card, and the ambient digest agree.
                    relevantThreshold = RelevanceThreshold.cut(model?.calibrationA, model?.calibrationB),
                    emergingAreas = areas,
                )
            }

        val uiState: StateFlow<TodayUiState> =
            combine(base, readingProgressRepository.observeContinueReading()) { state, continueReading ->
                state.copy(continueReading = continueReading)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

        fun refresh() = syncScheduler.syncNow()

        // --- "Recommended for you" shelf (PRS.3) ---

        /** The list disclosed on the Idle card and sent VERBATIM on the tap — so disclosed == sent, exactly. */
        private var cachedSeedIds: List<String> = emptyList()

        private val _recShelf = MutableStateFlow<RecShelfUiState>(RecShelfUiState.Hidden)
        val recShelf: StateFlow<RecShelfUiState> = _recShelf.asStateFlow()

        init {
            refreshSeedState()
        }

        /**
         * Recompute the seed set (cheap; no egress) → Hidden (nothing seedable) or Idle(count). Skipped
         * while a fetch is in flight or complete: once the user taps, the shelf is memoized for the
         * surface's life (mirrors the PaperDetail discover memoization) — a later save must not silently
         * reset an engaged shelf. `seedIds()` is idempotent per library state, so recomputes are safe.
         */
        private fun refreshSeedState() {
            if (_recShelf.value is RecShelfUiState.Loading || _recShelf.value is RecShelfUiState.Done) return
            viewModelScope.launch {
                val ids = recShelfRepository.seedIds()
                cachedSeedIds = ids
                _recShelf.value = if (ids.isEmpty()) RecShelfUiState.Hidden else RecShelfUiState.Idle(ids.size)
            }
        }

        /** Tap on the invitation card / Retry: send EXACTLY the disclosed [cachedSeedIds]. Egress happens here. */
        fun fetchRecommendations() {
            if (_recShelf.value is RecShelfUiState.Loading) return
            viewModelScope.launch { runFetch(cachedSeedIds.ifEmpty { recShelfRepository.seedIds() }) }
        }

        /** Explicit Refresh on a populated shelf: recompute seeds (library may have changed) then re-fetch. */
        fun refreshRecommendations() {
            if (_recShelf.value is RecShelfUiState.Loading) return
            viewModelScope.launch { runFetch(recShelfRepository.seedIds()) }
        }

        private suspend fun runFetch(ids: List<String>) {
            cachedSeedIds = ids
            if (ids.isEmpty()) {
                _recShelf.value = RecShelfUiState.Hidden
                return
            }
            _recShelf.value = RecShelfUiState.Loading
            // A queued 1.2s mutex slot could otherwise hang the shelf indefinitely; the timeout maps to a
            // NEUTRAL "couldn't reach" error (Upstream(null)) — deliberately distinct from Offline's copy.
            val result =
                withTimeoutOrNull(RECSHELF_TIMEOUT_MS) { recShelfRepository.recommend(ids) }
                    ?: RecShelfResult.Error(AppError.Upstream(null))
            _recShelf.value = RecShelfUiState.Done(result)
        }

        /** Per-row "Not interested": session-only removal from the visible Ready list. NEVER written to `paper_feedback`. */
        fun hideRecommendation(s2PaperId: String) {
            val current = _recShelf.value
            if (current is RecShelfUiState.Done && current.result is RecShelfResult.Ready) {
                val remaining = current.result.hits.filterNot { it.s2PaperId == s2PaperId }
                _recShelf.value = RecShelfUiState.Done(RecShelfResult.Ready(remaining))
            }
        }

        /** SPEC-CLAUDE-BRIDGE §5 weekly_review auto-selection: recent library adds + top inbox. */
        suspend fun weeklyReviewSelection(): List<String> {
            val weekAgo = java.time.Instant.now().minusSeconds(7L * 24 * 3600)
            val library =
                libraryRepository.observeLibrary().first()
                    .filter { it.addedAt.isAfter(weekAgo) }
                    .map { it.paper.ref.storageId }
            val inbox = uiState.value.items.take(10).map { it.paper.ref.storageId }
            return (library + inbox).distinct().take(20)
        }

        private val _triageEvent = MutableStateFlow<TriageEvent?>(null)

        /** Latest swipe action awaiting its undo snackbar; consume after showing. */
        val triageEvent: StateFlow<TriageEvent?> = _triageEvent.asStateFlow()

        fun save(item: InboxPaper) {
            viewModelScope.launch {
                inboxRepository.saveToLibrary(item.paper.ref.storageId)
                _triageEvent.value = TriageEvent(item.paper.ref.storageId, TriageKind.SAVED, item.state)
                // A first save flips the shelf Hidden→Idle; a later one updates the disclosed count.
                refreshSeedState()
            }
        }

        fun dismiss(item: InboxPaper) {
            viewModelScope.launch {
                inboxRepository.dismiss(item.paper.ref.storageId)
                _triageEvent.value = TriageEvent(item.paper.ref.storageId, TriageKind.DISMISSED, item.state)
            }
        }

        /** Toggle an explicit relevance thumb (P4.2). The visible icon state is the confirmation — no snackbar. */
        fun relevanceVote(
            item: InboxPaper,
            up: Boolean,
        ) {
            viewModelScope.launch {
                inboxRepository.setRelevanceVote(item.paper.ref.storageId, up)
                // A thumb-up is a positive seed; refresh the shelf's seed count if it's still un-tapped.
                refreshSeedState()
            }
        }

        /** Reverses a triage swipe exactly: library entry / inbox state / and any label the swipe wrote. */
        fun undo(event: TriageEvent) {
            viewModelScope.launch {
                when (event.kind) {
                    TriageKind.SAVED -> {
                        libraryRepository.unsave(event.paperId)
                        inboxRepository.restoreState(event.paperId, event.previousState)
                        // Undoing a save removes a positive seed — recompute the un-tapped shelf's count.
                        refreshSeedState()
                    }
                    // Dismiss also wrote a durable negative label — undoDismiss clears it (P4).
                    TriageKind.DISMISSED -> inboxRepository.undoDismiss(event.paperId, event.previousState)
                }
            }
        }

        fun consumeTriageEvent() {
            _triageEvent.value = null
        }
    }
