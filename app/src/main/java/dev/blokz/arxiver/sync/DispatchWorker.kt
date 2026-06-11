package dev.blokz.arxiver.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.blokz.arxiver.data.DispatchRepository
import dev.blokz.arxiver.data.DispatchSubmission

/**
 * Drains queued routine dispatches when connectivity returns
 * (SPEC-CLAUDE-BRIDGE §3). WorkManager's backoff supplies the retry spacing.
 */
@HiltWorker
class DispatchWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val dispatchRepository: DispatchRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            dispatchRepository.pruneHistory()
            var anyStillQueued = false
            for (dispatchId in dispatchRepository.queuedDispatchIds()) {
                if (isStopped) break
                when (dispatchRepository.attemptSend(dispatchId)) {
                    is DispatchSubmission.Queued -> anyStillQueued = true
                    else -> Unit
                }
            }
            return if (anyStillQueued) Result.retry() else Result.success()
        }

        companion object {
            const val UNIQUE = "dispatch_drain"
        }
    }
