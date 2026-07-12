package dev.blokz.arxiver.sync

import androidx.annotation.VisibleForTesting
import dev.blokz.arxiver.core.database.dao.InboxDao
import dev.blokz.arxiver.core.database.dao.RelevanceModelDao
import dev.blokz.arxiver.core.search.eval.RelevanceThreshold
import dev.blokz.arxiver.data.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Decides whether to post an ambient digest at the end of an [EmbeddingWorker] scoring pass (P-Ambient PA.1b),
 * and if so posts it exactly once. Pure of Android except the injected [DigestNotifier]; the clock is overridable
 * so the fatigue cap + exactly-once accounting are unit-testable without a device.
 *
 * The gate order is load-bearing: the permission check precedes the mark, so a denied/revoked grant never
 * "swallows" the backlog (the rows stay `digested_at IS NULL` for a later, permitted pass); and the rows are
 * stamped + the daily cursor advanced BEFORE the notification posts, so a crash loses at most one digest, never
 * double-fires.
 */
class DigestRunner
    @Inject
    constructor(
        private val inboxDao: InboxDao,
        private val relevanceModelDao: RelevanceModelDao,
        private val settings: SettingsRepository,
        private val notifier: DigestNotifier,
    ) {
        @VisibleForTesting
        internal var clock: () -> Long = { System.currentTimeMillis() }

        /**
         * @param suppressed true for a USER-initiated pass (a manual sync / embed-now) — never digest those, the
         * user is already looking; only the background periodic pass digests.
         */
        suspend fun maybePost(suppressed: Boolean) {
            if (suppressed) return
            if (!settings.digestEnabled.first()) return
            // No permission ⇒ don't touch the backlog, so a later grant still sees these rows as new.
            if (!notifier.canPost()) return

            val now = clock()
            if (now - settings.lastDigestPostedAt.first() < SettingsRepository.DIGEST_MIN_INTERVAL_MS) return

            val model = relevanceModelDao.current()
            val cut = RelevanceThreshold.cut(model?.calibrationA, model?.calibrationB)
            val recencyFloor = now - SettingsRepository.DIGEST_RECENCY_WINDOW_MS
            val rows = inboxDao.eligibleDigest(cut, recencyFloor)
            if (rows.isEmpty()) return

            // Crash-safe ordering: commit the cursor + per-row marks, THEN notify.
            inboxDao.markDigested(rows.map { it.paperId }, now)
            settings.setLastDigestPostedAt(now)
            notifier.notifyDigest(rows.size, rows.take(TITLE_COUNT).map { it.title })
        }

        companion object {
            /** How many titles the expanded notification body lists before "…and N more". */
            const val TITLE_COUNT = 3
        }
    }
