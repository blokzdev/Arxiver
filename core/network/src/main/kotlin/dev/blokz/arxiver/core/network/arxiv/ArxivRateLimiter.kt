package dev.blokz.arxiver.core.network.arxiv

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Global request spacing for export.arxiv.org (CLAUDE.md red line: >= 3s between
 * ALL arXiv requests, app-wide, no bypasses). Single instance app-wide; callers
 * suspend in FIFO order of lock acquisition.
 *
 * @param nowMs injectable monotonic clock for tests.
 */
class ArxivRateLimiter(
    private val minSpacingMs: Long = MIN_SPACING_MS,
    private val nowMs: () -> Long = System::nanoTime.let { { it() / 1_000_000 } },
) {
    private val mutex = Mutex()
    private var lastRequestAtMs = Long.MIN_VALUE / 2

    /** Suspends until a request slot is available, then claims it. */
    suspend fun acquire() {
        mutex.withLock {
            val waitMs = lastRequestAtMs + minSpacingMs - nowMs()
            if (waitMs > 0) delay(waitMs)
            lastRequestAtMs = nowMs()
        }
    }

    companion object {
        const val MIN_SPACING_MS = 3_000L
    }
}
