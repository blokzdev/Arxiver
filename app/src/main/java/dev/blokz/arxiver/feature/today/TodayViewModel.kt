package dev.blokz.arxiver.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.data.InboxPaper
import dev.blokz.arxiver.data.InboxRepository
import dev.blokz.arxiver.sync.SyncScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

        fun save(paperId: String) {
            viewModelScope.launch { inboxRepository.saveToLibrary(paperId) }
        }

        fun dismiss(paperId: String) {
            viewModelScope.launch { inboxRepository.dismiss(paperId) }
        }
    }
