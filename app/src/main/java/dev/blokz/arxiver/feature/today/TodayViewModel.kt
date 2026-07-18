package dev.blokz.arxiver.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.search.eval.RelevanceThreshold
import dev.blokz.arxiver.data.ContinueReadingUi
import dev.blokz.arxiver.data.DiscoverHit
import dev.blokz.arxiver.data.EmergingAreaUi
import dev.blokz.arxiver.data.InboxPaper
import dev.blokz.arxiver.data.InboxRepository
import dev.blokz.arxiver.data.ReadingProgressRepository
import dev.blokz.arxiver.data.RecShelfCache
import dev.blokz.arxiver.data.RecShelfRefreshPolicy
import dev.blokz.arxiver.data.RecShelfRepository
import dev.blokz.arxiver.data.RecShelfResult
import dev.blokz.arxiver.data.TrendingRepository
import dev.blokz.arxiver.data.toCached
import dev.blokz.arxiver.data.toHit
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    /**
     * Auto-refresh mode (PRS.4): rows served from the DataStore cache with a staleness label derived from
     * [fetchedAtMs]. [refreshing] = a background auto-refresh is in flight over these (stale-while-refresh).
     */
    data class Cached(
        val hits: List<DiscoverHit>,
        val fetchedAtMs: Long,
        val refreshing: Boolean,
    ) : RecShelfUiState
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
        private val settings: dev.blokz.arxiver.data.SettingsRepository,
        followsRepository: dev.blokz.arxiver.data.CategoryRepository,
        relevanceModelDao: dev.blokz.arxiver.core.database.dao.RelevanceModelDao,
        trendingRepository: TrendingRepository,
        readingProgressRepository: ReadingProgressRepository,
    ) : ViewModel() {
        /** Injectable-for-tests clock (auto-refresh TTL/backoff arithmetic); production uses the system clock. */
        internal var now: () -> Long = System::currentTimeMillis

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

        // --- "Recommended for you" shelf (PRS.3 tap-gated core + PRS.4 opt-in auto-refresh) ---

        /** The list disclosed on the Idle card and sent VERBATIM on the tap — so disclosed == sent, exactly. */
        private var cachedSeedIds: List<String> = emptyList()

        private val recShelfJson = Json { ignoreUnknownKeys = true }

        private val _recShelf = MutableStateFlow<RecShelfUiState>(RecShelfUiState.Hidden)
        val recShelf: StateFlow<RecShelfUiState> = _recShelf.asStateFlow()

        /** Whether the one-time inline auto-refresh invitation should show (auto OFF and not yet acted on). */
        val recShelfConsentAvailable: StateFlow<Boolean> =
            combine(
                settings.recShelfAutoRefreshEnabled,
                settings.recShelfConsentSeen,
            ) { enabled, seen -> !enabled && !seen }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        /** The auto-refresh pref, mirrored to a field so the tap-path (fetch/refresh) reads it synchronously. */
        private var autoEnabled = false

        init {
            // The tap-gated seed state (PRS.3) is the SYNCHRONOUS default — Today renders instantly without a
            // DataStore round-trip. A ONE-SHOT then reads the opt-in pref and, if auto is on, switches to the
            // cache-first Cached render. (One-shot, not a forever-collector: a live Settings→Today toggle
            // reflects on the next screen entry, not mid-session — an accepted deferral, recorded in the spec.
            // The inline consent card path IS immediate: `enableAutoRefresh` loads directly.)
            refreshSeedState()
            viewModelScope.launch {
                autoEnabled = settings.recShelfAutoRefreshEnabled.first()
                if (autoEnabled) autoLoadShelf()
            }
        }

        /**
         * Recompute the seed set (cheap; no egress) → Hidden (nothing seedable) or Idle(count). Skipped once
         * the shelf is engaged (Loading/Done/Cached) so a later save can't reset it. `seedIds()` is idempotent
         * per library state, so recomputes are safe.
         */
        private suspend fun computeSeedState() {
            if (isEngaged()) return
            val ids = recShelfRepository.seedIds()
            // Re-check AFTER the DB suspension (TOCTOU): a tap could have engaged the shelf while seedIds()
            // awaited; writing unconditionally would revert an in-flight fetch and un-gate a double egress.
            if (isEngaged()) return
            cachedSeedIds = ids
            _recShelf.value = if (ids.isEmpty()) RecShelfUiState.Hidden else RecShelfUiState.Idle(ids.size)
        }

        private fun refreshSeedState() {
            viewModelScope.launch { computeSeedState() }
        }

        private fun isEngaged(): Boolean =
            _recShelf.value.let {
                it is RecShelfUiState.Loading || it is RecShelfUiState.Done || it is RecShelfUiState.Cached
            }

        /** Auto mode: render fresh cache instantly, else (stale/none) refresh if the backoff window has elapsed. */
        private suspend fun autoLoadShelf() {
            val ids = recShelfRepository.seedIds()
            cachedSeedIds = ids
            if (ids.isEmpty()) {
                _recShelf.value = RecShelfUiState.Hidden
                return
            }
            val cache = readCache()
            val nowMs = now()
            if (cache != null && RecShelfRefreshPolicy.isFresh(nowMs, cache) && cache.hits.isNotEmpty()) {
                _recShelf.value = RecShelfUiState.Cached(cache.hits.map { it.toHit() }, cache.fetchedAtMs, refreshing = false)
                return
            }
            val stale = cache?.hits?.takeIf { it.isNotEmpty() }?.map { it.toHit() }
            if (RecShelfRefreshPolicy.mayAutoRefresh(nowMs, cache)) {
                runFetch(ids, persist = true, priorCache = cache)
            } else if (stale != null) {
                _recShelf.value = RecShelfUiState.Cached(stale, cache!!.fetchedAtMs, refreshing = false)
            } else {
                // Never succeeded and the backoff window hasn't elapsed → fall back to the tap invite (a
                // user tap bypasses the background backoff and can retry immediately).
                _recShelf.value = RecShelfUiState.Idle(ids.size)
            }
        }

        /** Tap on the invitation card / Retry: user-initiated, so it bypasses the auto-refresh backoff gate. */
        fun fetchRecommendations() {
            if (_recShelf.value is RecShelfUiState.Loading) return
            viewModelScope.launch {
                runFetch(
                    cachedSeedIds.ifEmpty { recShelfRepository.seedIds() },
                    persist = autoEnabled,
                    priorCache = if (autoEnabled) readCache() else null,
                )
            }
        }

        /** Explicit Refresh: recompute seeds (library may have changed), fetch, and (in auto mode) update the cache. */
        fun refreshRecommendations() {
            if (_recShelf.value is RecShelfUiState.Loading) return
            viewModelScope.launch {
                runFetch(
                    recShelfRepository.seedIds(),
                    persist = autoEnabled,
                    priorCache = if (autoEnabled) readCache() else null,
                )
            }
        }

        /**
         * Fetch and render. [persist] = auto mode: a Ready result caches (fetchedAt=now, backoff reset), an
         * Error advances backoff while keeping the last-good rows (stale-while-error), and empties/terminal
         * cache as a success-empty so we don't re-hit for the TTL. Tap-gated mode (persist=false) is the
         * PRS.3 behavior: Done(result), no cache.
         */
        private suspend fun runFetch(
            ids: List<String>,
            persist: Boolean,
            priorCache: RecShelfCache?,
        ) {
            cachedSeedIds = ids
            if (ids.isEmpty()) {
                _recShelf.value = RecShelfUiState.Hidden
                return
            }
            val stale = if (persist) priorCache?.hits?.takeIf { it.isNotEmpty() }?.map { it.toHit() } else null
            _recShelf.value =
                if (stale != null) {
                    RecShelfUiState.Cached(stale, priorCache!!.fetchedAtMs, refreshing = true)
                } else {
                    RecShelfUiState.Loading
                }
            val nowMs = now()
            // A queued 1.2s mutex slot could otherwise hang forever; the timeout maps to a NEUTRAL "couldn't
            // reach" error (Upstream(null)) — distinct from Offline. A stray throw (a dedup DB read / transport
            // rethrow) is caught so the shelf never sticks on the spinner and viewModelScope never crashes.
            val result =
                try {
                    withTimeoutOrNull(RECSHELF_TIMEOUT_MS) { recShelfRepository.recommend(ids) }
                        ?: RecShelfResult.Error(AppError.Upstream(null))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    RecShelfResult.Error(AppError.Unexpected(e))
                }
            if (persist) {
                persistAndRender(
                    result,
                    nowMs,
                    priorCache,
                    staleHits = stale,
                )
            } else {
                _recShelf.value = RecShelfUiState.Done(result)
            }
        }

        private suspend fun persistAndRender(
            result: RecShelfResult,
            nowMs: Long,
            priorCache: RecShelfCache?,
            staleHits: List<DiscoverHit>?,
        ) {
            when (result) {
                is RecShelfResult.Ready -> {
                    writeCache(RecShelfCache(fetchedAtMs = nowMs, hits = result.hits.map { it.toCached() }))
                    _recShelf.value = RecShelfUiState.Cached(result.hits, nowMs, refreshing = false)
                }
                is RecShelfResult.Error -> {
                    // Backoff advances; the last SUCCESSFUL rows/time are preserved (stale-while-error).
                    val attempts = (priorCache?.failedAttempts ?: 0) + 1
                    writeCache(
                        RecShelfCache(
                            fetchedAtMs = priorCache?.fetchedAtMs ?: 0,
                            hits = priorCache?.hits ?: emptyList(),
                            failedAttempts = attempts,
                            nextAllowedAtMs = RecShelfRefreshPolicy.nextAllowedAt(nowMs, attempts),
                        ),
                    )
                    _recShelf.value =
                        if (staleHits != null && priorCache != null) {
                            RecShelfUiState.Cached(staleHits, priorCache.fetchedAtMs, refreshing = false)
                        } else {
                            RecShelfUiState.Done(result)
                        }
                }
                else -> {
                    // Empties + NotRecommendable = the server answered with nothing to show. Cache a
                    // success-empty (resets the TTL clock) so we don't re-hit for 24h, and show the honest note.
                    writeCache(RecShelfCache(fetchedAtMs = nowMs, hits = emptyList()))
                    _recShelf.value = RecShelfUiState.Done(result)
                }
            }
        }

        private suspend fun readCache(): RecShelfCache? =
            settings.recShelfCache.first()?.let {
                runCatching { recShelfJson.decodeFromString<RecShelfCache>(it) }.getOrNull()
            }

        private suspend fun writeCache(cache: RecShelfCache) {
            settings.setRecShelfCache(recShelfJson.encodeToString(cache))
        }

        /** Accept the one-time inline auto-refresh invitation → persist the opt-in and load the shelf immediately. */
        fun enableAutoRefresh() {
            viewModelScope.launch {
                settings.setRecShelfConsentSeen()
                settings.setRecShelfAutoRefreshEnabled(true)
                autoEnabled = true
                autoLoadShelf()
            }
        }

        /** Dismiss the one-time invitation without enabling — never nag again; the tap-gated shelf stays. */
        fun dismissAutoRefreshInvite() {
            viewModelScope.launch { settings.setRecShelfConsentSeen() }
        }

        /** Per-row "Not interested": session-only removal from the visible Ready/Cached rows. NEVER written to `paper_feedback`. */
        fun hideRecommendation(s2PaperId: String) {
            when (val current = _recShelf.value) {
                is RecShelfUiState.Done ->
                    if (current.result is RecShelfResult.Ready) {
                        reofferOrShow(current.result.hits.filterNot { it.s2PaperId == s2PaperId }) {
                            RecShelfUiState.Done(RecShelfResult.Ready(it))
                        }
                    }
                is RecShelfUiState.Cached ->
                    reofferOrShow(current.hits.filterNot { it.s2PaperId == s2PaperId }) {
                        current.copy(hits = it)
                    }
                else -> Unit
            }
        }

        /** Hiding the LAST row must not leave a collapsed dead section — reset to a re-offerable state instead. */
        private fun reofferOrShow(
            remaining: List<DiscoverHit>,
            keep: (List<DiscoverHit>) -> RecShelfUiState,
        ) {
            if (remaining.isEmpty()) {
                _recShelf.value = RecShelfUiState.Hidden
                refreshSeedState()
            } else {
                _recShelf.value = keep(remaining)
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
