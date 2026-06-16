package dev.blokz.arxiver.feature.background

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.sync.BackgroundTask
import dev.blokz.arxiver.sync.BackgroundTaskMonitor
import dev.blokz.arxiver.sync.SyncScheduler
import dev.blokz.arxiver.sync.TaskKind
import dev.blokz.arxiver.sync.uniqueWorkNameFor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Observes [BackgroundTaskMonitor] and lets the user cancel / retry each kind of work (UX2.7). */
@HiltViewModel
class BackgroundTasksViewModel
    @Inject
    constructor(
        monitor: BackgroundTaskMonitor,
        private val syncScheduler: SyncScheduler,
    ) : ViewModel() {
        val tasks: StateFlow<List<BackgroundTask>> =
            monitor.tasks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        fun cancel(kind: TaskKind) {
            syncScheduler.cancelUnique(uniqueWorkNameFor(kind))
        }

        fun retry(kind: TaskKind) {
            when (kind) {
                TaskKind.FOLLOW_SYNC -> syncScheduler.syncNow()
                TaskKind.EMBEDDING, TaskKind.EMBEDDING_MODEL_DOWNLOAD -> syncScheduler.embedNow()
                TaskKind.GEMMA_DOWNLOAD -> syncScheduler.downloadOnDeviceModel()
            }
        }
    }
