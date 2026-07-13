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
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.PaperRef
import dev.blokz.arxiver.core.model.PaperSource
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.model.normalizeDoi
import dev.blokz.arxiver.core.model.resolvePaperRef
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
        /**
         * The outcome of syncing one follow (PFP.3). [Fetched] carries the delivered count so a zero-delivery sync
         * can bump the health streak while a *failure* never does; [Failed] is a fetch error (retry, cursor frozen);
         * [Skipped] is a config-dead follow (bad origin) that neither retries nor touches the streak.
         */
        private sealed interface SyncResult {
            @JvmInline
            value class Fetched(val count: Int) : SyncResult

            data object Failed : SyncResult

            data object Skipped : SyncResult
        }

        override suspend fun doWork(): Result {
            val follows = followDao.enabledFollows()
            if (follows.isEmpty()) return Result.success()

            var anyFailure = false
            val now = Instant.now().toEpochMilli()
            follows.forEach { follow ->
                when (val outcome = syncFollow(follow)) {
                    // Only a real fetch touches the cursor + streak; count==0 bumps, any delivery resets.
                    is SyncResult.Fetched -> followDao.markSynced(follow.id, now, outcome.count)
                    SyncResult.Failed -> anyFailure = true
                    SyncResult.Skipped -> Unit // config-dead follow: freeze cursor AND streak (never retries)
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
        private suspend fun syncFollow(follow: FollowEntity): SyncResult =
            if (follow.origin == Source.ARXIV.wire) {
                syncFollowViaArxiv(follow)
            } else {
                syncFollowViaBackend(follow)
            }

        private suspend fun syncFollowViaArxiv(follow: FollowEntity): SyncResult {
            val query =
                when (follow.type) {
                    FollowEntity.TYPE_CATEGORY -> ArxivQuery.category(follow.value, maxResults = PAGE_SIZE)
                    FollowEntity.TYPE_AUTHOR -> ArxivQuery.author(follow.value, maxResults = PAGE_SIZE)
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
                    // Count is pre-inbox-IGNORE: a working-but-redundant follow (papers already seen) still reports
                    // delivered>0 so it resets the health streak, not reads as dead (PFP.3).
                    SyncResult.Fetched(fresh.size)
                }
                is AppResult.Failure -> {
                    Timber.w("Follow sync failed for ${follow.type}:${follow.value} — ${result.error}")
                    SyncResult.Failed
                }
            }
        }

        /**
         * A non-arXiv follow: browse its [PreprintBackend] from the last-synced date (or a first-sync window),
         * paging until exhausted or a bound, then upsert + inbox the fresh papers. The inbox `IGNORE` conflict
         * dedups an already-seen paper on re-sync; a `false` return retries only this follow.
         */
        private suspend fun syncFollowViaBackend(follow: FollowEntity): SyncResult {
            val source = Source.entries.firstOrNull { it.wire == follow.origin }
            val backend = source?.let { preprintBackends.backendFor(it) }
            if (source == null || backend == null) {
                Timber.w("Follow sync: unknown/unsupported origin '${follow.origin}' — skipping")
                return SyncResult.Skipped // config-dead: never retries, never touches the streak
            }
            val category = follow.value.takeIf { follow.type == FollowEntity.TYPE_CATEGORY }
            val sinceIso = sinceIsoFor(follow)

            val hits = LinkedHashMap<String, PreprintHit>() // dedup by NORMALIZED DOI, preserve order
            var cursor: String? = null
            var pages = 0
            do {
                when (val r = backend.browse(source, category, sinceIso, cursor)) {
                    is AppResult.Success -> {
                        // Key on the normalized DOI, falling back to the source-native id — a DOI-less source (OSF) would
                        // otherwise collapse every hit onto one key and deliver a single paper (PE.1b).
                        r.value.hits.forEach { hits.putIfAbsent(normalizeDoi(it.doi) ?: it.nativeId, it) }
                        cursor = r.value.nextCursor
                    }
                    is AppResult.Failure -> {
                        Timber.w("Follow sync failed for ${follow.origin}:${follow.value} — ${r.error}")
                        return SyncResult.Failed
                    }
                }
            } while (cursor != null && ++pages < MAX_FOLLOW_PAGES && hits.size < FIRST_SYNC_LIMIT)

            val fresh = hits.values.take(FIRST_SYNC_LIMIT)
            val now = Instant.now().toEpochMilli()
            val inboxRows =
                fresh.map { hit ->
                    // One canonical ref per hit (P-FeedPolish cross-source de-dup) — used for BOTH the paper row
                    // and the inbox row, so a cross-posted paper can never fork into two rows.
                    val ref = canonicalRef(hit)
                    // Degraded-metadata guard: a hit that collapsed onto a bare arXiv id must not clobber a richer
                    // native-arXiv row (toPaper hardcodes primaryCategory=""/pdfUrl). Insert-if-absent for that case.
                    if (!(ref is ArxivRef && paperDao.paperById(ref.storageId) != null)) {
                        val paper = hit.toPaper(ref)
                        paperDao.upsertPaperWithRelations(paper.toEntity(), paper.authors, paper.categories)
                    }
                    InboxItemEntity(paperId = ref.storageId, followId = follow.id, arrivedAt = now)
                }
            inboxDao.insertAll(inboxRows)
            // Pre-inbox-IGNORE count (same contract as the arXiv path): the feed returned this many in-window
            // hits, so a redundant-but-live follow resets its health streak rather than reading as dead (PFP.3).
            return SyncResult.Fetched(fresh.size)
        }

        /**
         * Resolve one canonical [PaperRef] for a hit (P-FeedPolish de-dup): an arXiv cross-id wins (a cross-posted
         * arXiv paper keys under the bare arXiv id via [resolvePaperRef]); else reuse an already-stored row that
         * shares the DOI (arXiv-origin preferred); else the source's own `ExternalRef`.
         */
        private suspend fun canonicalRef(hit: PreprintHit): PaperRef {
            val resolved = resolvePaperRef(arxivId = hit.arxivId, origin = hit.origin, nativeId = hit.nativeId)
            if (resolved is ArxivRef) return resolved
            normalizeDoi(hit.doi)?.let { norm ->
                paperDao.paperIdByDoi(norm)?.let { return PaperRef.fromStorageId(it) }
            }
            return resolved // ExternalRef(origin, doi)
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

        private fun PreprintHit.toPaper(ref: PaperRef): Paper {
            val published =
                publishedIso
                    ?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull() }
                    ?: Instant.EPOCH
            return Paper(
                ref = ref,
                latestVersion = version?.toIntOrNull() ?: 1,
                title = title,
                abstract = abstract,
                publishedAt = published,
                updatedAt = published,
                // The source's own discipline label (OpenAlex Field / bio-med native category), not "" (PE.0) —
                // a blank here rendered an empty category chip on every non-arXiv row.
                primaryCategory = fieldName.orEmpty(),
                categories = listOfNotNull(fieldName),
                authors = authors,
                doi = doi,
                landingUrl = landingUrl,
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
