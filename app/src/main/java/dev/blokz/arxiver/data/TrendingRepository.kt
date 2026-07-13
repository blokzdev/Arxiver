package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.dao.InboxDao
import dev.blokz.arxiver.core.search.emerging.TrendingConfig
import dev.blokz.arxiver.core.search.emerging.TrendingRanker
import dev.blokz.arxiver.core.search.emerging.TrendingWindowPaper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject

/** A surfaced emerging area, cached for the UI (P-Discover2 PD.3b). The UI maps [category] to a human name. */
@Serializable
data class EmergingAreaUi(
    val category: String,
    val recentDocs: Int,
    val drivingPaperIds: List<String>,
)

@Serializable
private data class TrendingCache(
    val computedForDay: Long,
    val previousDayKeys: List<String>,
    val areas: List<EmergingAreaUi>,
)

/**
 * "Emerging in your areas" (P-Discover2 PD.3b) — computes the honest area-emergence at most ONCE/day on a background
 * worker and caches the result; the UI only ever READS the cache (mirrors the ambient-digest pattern: worker computes,
 * UI reads). This is both the performance design (never per-compose over an un-indexed `published_at`) and the anti-
 * churn ethos requirement (the shelf changes at most daily). Network-free; never leaves the device.
 */
class TrendingRepository
    @Inject
    constructor(
        private val inboxDao: InboxDao,
        private val settings: SettingsRepository,
        private val dispatchers: DispatcherProvider,
    ) {
        private val config = TrendingConfig()
        private val ranker = TrendingRanker(config)
        private val json = Json { ignoreUnknownKeys = true }

        /** Recompute if we haven't already today AND the shelf is enabled. Called from the sync worker tail. */
        suspend fun recomputeIfStale(now: Instant) {
            if (!settings.trendingEnabled.first()) return
            val today = now.toEpochMilli() / DAY_MS
            val cache = readCache()
            if (cache != null && cache.computedForDay >= today) return

            val baselineFrom = now.toEpochMilli() - (config.recentWindowDays + config.baselineWindowDays) * DAY_MS
            val rows = withContext(dispatchers.io) { inboxDao.trendingWindowRows(baselineFrom) }
            val papers =
                rows.groupBy { it.paperId }.map { (id, group) ->
                    val first = group.first()
                    TrendingWindowPaper(
                        paperId = id,
                        categories = group.map { it.categoryCode }.distinct(),
                        publishedAt = first.publishedAt,
                        authorsLine = first.authorsLine,
                        followId = first.followId,
                        followCreatedAt = first.followCreatedAt,
                    )
                }
            val ranked =
                withContext(dispatchers.default) {
                    ranker.rank(papers, now, previousDayKeys = cache?.previousDayKeys?.toSet() ?: emptySet())
                }
            settings.setTrendingCache(
                json.encodeToString(
                    TrendingCache(
                        computedForDay = today,
                        // Today's survivors become tomorrow's hysteresis set (an area must persist a day to show).
                        previousDayKeys = ranked.map { it.category },
                        // The UI renders only CONFIRMED (2-day-persistent) areas — calm over churn.
                        areas =
                            ranked.filter { it.confirmed }.map {
                                EmergingAreaUi(
                                    it.category,
                                    it.recentDocs,
                                    it.drivingPaperIds,
                                )
                            },
                    ),
                ),
            )
        }

        /** The areas to render — empty when the toggle is off or nothing has cleared the bar (the common, honest state). */
        fun observeAreas(): Flow<List<EmergingAreaUi>> =
            combine(settings.trendingEnabled, settings.trendingCache) { enabled, cacheJson ->
                if (!enabled || cacheJson == null) {
                    emptyList()
                } else {
                    runCatching { json.decodeFromString<TrendingCache>(cacheJson).areas }.getOrDefault(emptyList())
                }
            }

        private suspend fun readCache(): TrendingCache? =
            settings.trendingCache.first()?.let { runCatching { json.decodeFromString<TrendingCache>(it) }.getOrNull() }

        private companion object {
            const val DAY_MS = 86_400_000L
        }
    }
