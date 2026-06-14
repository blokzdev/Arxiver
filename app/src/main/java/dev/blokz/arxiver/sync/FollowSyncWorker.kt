package dev.blokz.arxiver.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.database.dao.FollowDao
import dev.blokz.arxiver.core.database.dao.InboxDao
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.database.entity.FollowEntity
import dev.blokz.arxiver.core.database.entity.InboxItemEntity
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.model.PaperSource
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivQuery
import timber.log.Timber
import java.time.Instant

/**
 * Pulls new papers for every enabled follow into the inbox (SPEC-DATA §2
 * `follows.last_synced_at` is the cursor; ARCHITECTURE §3.5). Requests share
 * the app-wide arXiv rate limiter, so a long follow list simply takes longer.
 */
@HiltWorker
class FollowSyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val followDao: FollowDao,
        private val paperDao: PaperDao,
        private val inboxDao: InboxDao,
        private val arxivApiClient: ArxivApiClient,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val follows = followDao.enabledFollows()
            if (follows.isEmpty()) return Result.success()

            var anyFailure = false
            follows.forEach { follow ->
                when (syncFollow(follow)) {
                    true -> followDao.markSynced(follow.id, Instant.now().toEpochMilli())
                    false -> anyFailure = true
                }
            }
            inboxDao.pruneDismissed(cutoff = Instant.now().minusSeconds(DISMISSED_RETENTION_S).toEpochMilli())

            // Per-follow cursors make retry cheap: only failed follows refetch. But bound
            // the retries — a persistently failing follow (rate limit, dead category) must
            // not keep this one-shot ENQUEUED forever (that pinned the Today sync spinner).
            // After the cap we report success; the periodic sync picks the follow up later.
            return if (anyFailure && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.success()
            }
        }

        private suspend fun syncFollow(follow: FollowEntity): Boolean {
            val query =
                when (follow.type) {
                    FollowEntity.TYPE_CATEGORY -> ArxivQuery.category(follow.value, maxResults = PAGE_SIZE)
                    FollowEntity.TYPE_AUTHOR -> ArxivQuery.raw("au:\"${follow.value}\"", maxResults = PAGE_SIZE)
                    else -> ArxivQuery.raw(follow.value, maxResults = PAGE_SIZE)
                }
            return when (val result = arxivApiClient.fetch(query, PaperSource.FOLLOW)) {
                is AppResult.Success -> {
                    val cutoff = follow.lastSyncedAt
                    val fresh =
                        result.value.papers.filter { paper ->
                            cutoff == null || paper.publishedAt.toEpochMilli() > cutoff ||
                                paper.updatedAt.toEpochMilli() > cutoff
                        }.let { if (cutoff == null) it.take(FIRST_SYNC_LIMIT) else it }

                    fresh.forEach { paper ->
                        paperDao.upsertPaperWithRelations(paper.toEntity(), paper.authors, paper.categories)
                    }
                    val now = Instant.now().toEpochMilli()
                    inboxDao.insertAll(
                        fresh.map {
                            InboxItemEntity(paperId = it.id.value, followId = follow.id, arrivedAt = now)
                        },
                    )
                    true
                }
                is AppResult.Failure -> {
                    Timber.w("Follow sync failed for ${follow.type}:${follow.value} — ${result.error}")
                    false
                }
            }
        }

        companion object {
            const val UNIQUE_PERIODIC = "follow_sync_periodic"
            const val UNIQUE_ONESHOT = "follow_sync_now"
            private const val MAX_RETRY_ATTEMPTS = 3
            private const val PAGE_SIZE = 50
            private const val FIRST_SYNC_LIMIT = 20
            private const val DISMISSED_RETENTION_S = 30L * 24 * 3600
        }
    }
