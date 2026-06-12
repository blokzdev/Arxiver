package dev.blokz.arxiver.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.data.InboxPaper
import dev.blokz.arxiver.data.InboxRepository
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
)

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
    ) : ViewModel() {
        val uiState: StateFlow<TodayUiState> =
            combine(
                inboxRepository.observeInbox(),
                syncScheduler.observeSyncRunning(),
                followsRepository.observeGroupedCategories(),
            ) { items, syncing, grouped ->
                TodayUiState(
                    items = items,
                    syncing = syncing,
                    hasFollows = grouped.values.flatten().any { it.followed },
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

        fun refresh() = syncScheduler.syncNow()

        /** SPEC-CLAUDE-BRIDGE §5 weekly_review auto-selection: recent library adds + top inbox. */
        suspend fun weeklyReviewSelection(): List<String> {
            val weekAgo = java.time.Instant.now().minusSeconds(7L * 24 * 3600)
            val library =
                libraryRepository.observeLibrary().first()
                    .filter { it.addedAt.isAfter(weekAgo) }
                    .map { it.paper.id.value }
            val inbox = uiState.value.items.take(10).map { it.paper.id.value }
            return (library + inbox).distinct().take(20)
        }

        private val _triageEvent = MutableStateFlow<TriageEvent?>(null)

        /** Latest swipe action awaiting its undo snackbar; consume after showing. */
        val triageEvent: StateFlow<TriageEvent?> = _triageEvent.asStateFlow()

        fun save(item: InboxPaper) {
            viewModelScope.launch {
                inboxRepository.saveToLibrary(item.paper.id.value)
                _triageEvent.value = TriageEvent(item.paper.id.value, TriageKind.SAVED, item.state)
            }
        }

        fun dismiss(item: InboxPaper) {
            viewModelScope.launch {
                inboxRepository.dismiss(item.paper.id.value)
                _triageEvent.value = TriageEvent(item.paper.id.value, TriageKind.DISMISSED, item.state)
            }
        }

        /** Reverses a triage swipe exactly: library entry and inbox state. */
        fun undo(event: TriageEvent) {
            viewModelScope.launch {
                if (event.kind == TriageKind.SAVED) libraryRepository.unsave(event.paperId)
                inboxRepository.restoreState(event.paperId, event.previousState)
            }
        }

        fun consumeTriageEvent() {
            _triageEvent.value = null
        }
    }
