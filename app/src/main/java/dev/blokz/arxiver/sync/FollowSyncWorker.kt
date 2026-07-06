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
import dev.blokz.arxiver.core.model.ExternalRef
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.PaperSource
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.network.PreprintBackendRegistry
import dev.blokz.arxiver.core.network.PreprintHit
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivQuery
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Pulls new papers for every enabled follow into the inbox (SPEC-DATA §2 `follows.last_synced_at` is the
 * cursor; ARCHITECTURE §3.5). Origin-dispatched (P-Feeds PF.2): an `arxiv` follow rides the native Atom API;
 * a `biorxiv`/`medrxiv`/`chemrxiv`/… follow rides its [PreprintBackend] (native api.biorxiv.org or OpenAlex).
 * Requests share each host's politeness spacing, so a long follow list simply takes longer.
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
        private val preprintBackends: PreprintBackendRegistry,
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

        /** Origin dispatch: arXiv → native Atom; every other source → its [PreprintBackend]. */
        private suspend fun syncFollow(follow: FollowEntity): Boolean =
            if (follow.origin == Source.ARXIV.wire) {
                syncFollowViaArxiv(follow)
            } else {
                syncFollowViaBackend(follow)
            }

        private suspend fun syncFollowViaArxiv(follow: FollowEntity): Boolean {
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
                        fresh.map { InboxItemEntity(paperId = it.id.value, followId = follow.id, arrivedAt = now) },
                    )
                    true
                }
                is AppResult.Failure -> {
                    Timber.w("Follow sync failed for ${follow.type}:${follow.value} — ${result.error}")
                    false
                }
            }
        }

        /**
         * A non-arXiv follow: browse its [PreprintBackend] from the last-synced date (or a first-sync window),
         * paging until exhausted or a bound, then upsert + inbox the fresh papers. The inbox `IGNORE` conflict
         * dedups an already-seen paper on re-sync; a `false` return retries only this follow.
         */
        private suspend fun syncFollowViaBackend(follow: FollowEntity): Boolean {
            val source = Source.entries.firstOrNull { it.wire == follow.origin }
            val backend = source?.let { preprintBackends.backendFor(it) }
            if (source == null || backend == null) {
                Timber.w("Follow sync: unknown/unsupported origin '${follow.origin}' — skipping")
                return true // not retryable — a bad origin never recovers
            }
            val category = follow.value.takeIf { follow.type == FollowEntity.TYPE_CATEGORY }
            val sinceIso = sinceIsoFor(follow)

            val hits = LinkedHashMap<String, PreprintHit>() // dedup by DOI, preserve order
            var cursor: String? = null
            var pages = 0
            do {
                when (val r = backend.browse(source, category, sinceIso, cursor)) {
                    is AppResult.Success -> {
                        r.value.hits.forEach { hits.putIfAbsent(it.doi, it) }
                        cursor = r.value.nextCursor
                    }
                    is AppResult.Failure -> {
                        Timber.w("Follow sync failed for ${follow.origin}:${follow.value} — ${r.error}")
                        return false
                    }
                }
            } while (cursor != null && ++pages < MAX_FOLLOW_PAGES && hits.size < FIRST_SYNC_LIMIT)

            val fresh = hits.values.take(FIRST_SYNC_LIMIT)
            fresh.forEach { hit ->
                val paper = hit.toPaper()
                paperDao.upsertPaperWithRelations(paper.toEntity(), paper.authors, paper.categories)
            }
            val now = Instant.now().toEpochMilli()
            inboxDao.insertAll(
                fresh.map {
                    InboxItemEntity(
                        paperId = ExternalRef(it.origin, it.doi).storageId,
                        followId = follow.id,
                        arrivedAt = now,
                    )
                },
            )
            return true
        }

        /** The `YYYY-MM-DD` lower bound for a backend browse — the last-synced date, or a first-sync window. */
        private fun sinceIsoFor(follow: FollowEntity): String {
            val cutoff = follow.lastSyncedAt
            val date =
                if (cutoff == null) {
                    LocalDate.now(ZoneOffset.UTC).minusDays(FIRST_SYNC_DAYS)
                } else {
                    Instant.ofEpochMilli(cutoff).atZone(ZoneOffset.UTC).toLocalDate()
                }
            return date.toString()
        }

        private fun PreprintHit.toPaper(): Paper {
            val published =
                publishedIso
                    ?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull() }
                    ?: Instant.EPOCH
            return Paper(
                ref = ExternalRef(origin, doi),
                latestVersion = version?.toIntOrNull() ?: 1,
                title = title,
                abstract = abstract,
                publishedAt = published,
                updatedAt = published,
                primaryCategory = "",
                categories = emptyList(),
                authors = authors,
                doi = doi,
                pdfUrl = oaPdfUrl ?: "",
                source = PaperSource.FOLLOW,
            )
        }

        companion object {
            const val UNIQUE_PERIODIC = "follow_sync_periodic"
            const val UNIQUE_ONESHOT = "follow_sync_now"
            private const val MAX_RETRY_ATTEMPTS = 3
            private const val PAGE_SIZE = 50
            private const val FIRST_SYNC_LIMIT = 20
            private const val FIRST_SYNC_DAYS = 14L
            private const val MAX_FOLLOW_PAGES = 5
            private const val DISMISSED_RETENTION_S = 30L * 24 * 3600
        }
    }
