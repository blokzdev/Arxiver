package dev.blokz.arxiver.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.search.eval.RelevanceThreshold
import dev.blokz.arxiver.data.ContinueReadingUi
import dev.blokz.arxiver.data.EmergingAreaUi
import dev.blokz.arxiver.data.InboxPaper
import dev.blokz.arxiver.data.InboxRepository
import dev.blokz.arxiver.data.ReadingProgressRepository
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
            }
        }

        /** Reverses a triage swipe exactly: library entry / inbox state / and any label the swipe wrote. */
        fun undo(event: TriageEvent) {
            viewModelScope.launch {
                when (event.kind) {
                    TriageKind.SAVED -> {
                        libraryRepository.unsave(event.paperId)
                        inboxRepository.restoreState(event.paperId, event.previousState)
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
