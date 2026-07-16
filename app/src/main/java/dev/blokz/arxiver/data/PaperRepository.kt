package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.common.map
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.database.toDomain
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.database.toListDomain
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.ExternalPaperDraft
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.PaperRef
import dev.blokz.arxiver.core.model.PaperSource
import dev.blokz.arxiver.core.model.PdfAccess
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.model.pdfAccess
import dev.blokz.arxiver.core.model.resolvePaperRef
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivFeed
import dev.blokz.arxiver.core.network.arxiv.ArxivQuery
import dev.blokz.arxiver.core.network.arxiv.SearchFilter
import dev.blokz.arxiver.core.network.openalex.OaFulltextResolver
import dev.blokz.arxiver.core.network.openalex.OpenAlexClient
import dev.blokz.arxiver.core.network.openalex.toPaper
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** A page of remote results. */
data class PaperPage(
    val papers: List<Paper>,
    val totalResults: Int,
    val nextStart: Int?,
)

@Singleton
class PaperRepository
    @Inject
    constructor(
        private val arxivApiClient: ArxivApiClient,
        private val paperDao: PaperDao,
        private val openAlexClient: OpenAlexClient,
        private val dispatchers: DispatcherProvider,
    ) {
        /**
         * Keyword search on a non-arXiv source (P-Explorer PE.3) — ONE OpenAlex call per explicit submit (the
         * metering red line), optionally Field-narrowed server-side (still one call). **Un-paginated v1:**
         * `nextStart = null` structurally no-ops the list's auto-load-more, so a scroll can never bill a BYOK
         * key. Results are mapped, NOT cached — persistence happens on first interaction ([cacheSearchHit]).
         */
        suspend fun searchExternal(
            source: Source,
            query: String,
            fieldToken: String? = null,
        ): AppResult<PaperPage> {
            val sid = requireNotNull(OpenAlexClient.searchSidFor(source)) { "not a searchable source: $source" }
            return openAlexClient.search(query, EXTERNAL_PAGE_SIZE, sid, fieldToken).map { r ->
                val papers = r.results.mapNotNull { it.toPaper(source) }.distinctBy { it.ref.storageId }
                PaperPage(papers, totalResults = r.meta.count ?: papers.size, nextStart = null)
            }
        }

        /**
         * Persist an Explore search hit on first interaction (open/save/dispatch — never on render). An
         * arXiv-collapsed cross-post delegates to the cache-first [paper] path so the FULL native record is
         * stored (one rate-limited fetch on a rare explicit action; a thin OpenAlex row must never permanently
         * shadow the native one) — offline falls back to the mapped row. Everything else goes through the atomic
         * DAO reuse-or-insert, which can re-key onto an existing same-DOI row — **callers must use the RETURNED
         * paper's storageId**, never the hit's own.
         */
        suspend fun cacheSearchHit(hit: Paper): Paper {
            if (hit.ref is ArxivRef) paper(hit.ref)?.let { return it }
            val id = paperDao.insertPaperIfAbsentWithRelations(hit.toEntity(), hit.authors, hit.categories)
            return checkNotNull(paperDao.paperById(id)).toListDomain()
        }

        /** Latest papers in a category, newest first. Results are cached locally. */
        suspend fun categoryLatest(
            code: String,
            start: Int = 0,
        ): AppResult<PaperPage> = fetchAndCache(ArxivQuery.category(code, start = start), PaperSource.SEARCH)

        /** Locally-cached papers for a category (newest first) — instant Browse, no network. */
        suspend fun cachedCategory(
            code: String,
            limit: Int = CACHED_PAGE,
        ): List<Paper> = paperDao.papersByCategory(code, limit).map { it.toListDomain() }

        /** Online arXiv search (field prefixes and booleans pass through). */
        suspend fun searchArxiv(
            query: String,
            start: Int = 0,
        ): AppResult<PaperPage> = fetchAndCache(ArxivQuery.search(query, start = start), PaperSource.SEARCH)

        /** Online arXiv search from structured UI filters (scoped term + categories + date + sort). */
        suspend fun searchArxiv(
            filter: SearchFilter,
            start: Int = 0,
            maxResults: Int = ArxivQuery.DEFAULT_PAGE_SIZE,
        ): AppResult<PaperPage> =
            fetchAndCache(ArxivQuery.fromFilter(filter, start = start, maxResults = maxResults), PaperSource.SEARCH)

        /**
         * Paper for the reader/detail screens: local cache first (keyed by the opaque [PaperRef.storageId],
         * origin-blind), then — for arXiv only — a network refresh. A non-arXiv cache miss returns null with
         * ZERO network: there is no `export.arxiv.org` query for a `chemrxiv:…` id, and a non-arXiv paper only
         * reaches a reader after import (a cache hit), so the null-on-miss path is the enforced safety net.
         * Returns null when the paper is unknown locally AND unfetchable.
         */
        suspend fun paper(ref: PaperRef): Paper? {
            paperDao.paperWithRelations(ref.storageId)?.let { return it.toDomain() }
            val arxiv = ref.arxivIdOrNull ?: return null
            val fetched = fetchAndCache(ArxivQuery.byIds(listOf(arxiv.value)), PaperSource.SHARE_IN)
            return (fetched as? AppResult.Success)?.value?.papers?.firstOrNull()
        }

        @Deprecated("Pass a PaperRef", ReplaceWith("paper(ArxivRef(id))"))
        suspend fun paper(id: ArxivId): Paper? = paper(ArxivRef(id))

        /**
         * Resolve the best FREE open-access fulltext for a browser-gated preprint (P-OA) — preferring the
         * *published* version-of-record — as a browser-openable URL. **Exactly one** OpenAlex call per invocation,
         * and only ever from an explicit user tap (the metering red line): the `search(sourceId = null)` host-free
         * title crosswalk subsumes both OpenAlex topologies (a merged version-of-record, and a separate published
         * sibling). No new egress host — the resolved PDF opens in the external browser, never the in-app reader.
         *
         * Zero-call short-circuits: a non-browser-tier source (arXiv/bio/med read their PDF in-app) or a blank
         * title returns [OaResult.None] without touching the network. A transient failure/timeout is
         * [OaResult.Error] (retryable) — distinct from a genuine [OaResult.None] so the UI never shows a false
         * "no free version" while offline.
         */
        suspend fun resolveOaFulltext(paper: Paper): OaResult {
            if (paper.ref.origin.pdfAccess() != PdfAccess.BROWSER || paper.title.isBlank()) return OaResult.None
            val response =
                withTimeoutOrNull(OA_TIMEOUT_MS) {
                    openAlexClient.search(paper.title, OA_SCAN_LIMIT, sourceId = null)
                } ?: return OaResult.Error(AppError.Offline)
            return when (response) {
                is AppResult.Success ->
                    withContext(dispatchers.default) {
                        val match = OaFulltextResolver.pick(OaFulltextResolver.queryFor(paper), response.value.results)
                        match?.let { OaResult.Found(it.pdfUrl, it.journalName, it.versionOfRecord) } ?: OaResult.None
                    }
                is AppResult.Failure -> OaResult.Error(response.error)
            }
        }

        /**
         * Persist a non-arXiv paper captured from a search hit ([ExternalPaperDraft]) as a real `papers` row,
         * with NO network (the draft already carries the full metadata). Idempotent via `@Upsert`. The ref is
         * chosen through [resolvePaperRef] — the single de-dup chokepoint — so a source that ever carries an
         * arXiv cross-id would key under the bare arXiv id instead of forking. (The old note here claimed
         * "chemRxiv never does" — measured 2026-07-10, only 0.0097% of chemRxiv works carry an arXiv
         * `locations[]` entry, so the crosswalk is near-inert and the normalized-DOI reuse below is what
         * actually prevents the fork.)
         *
         * `primaryCategory = ""` + `categories = emptyList()` is a deliberate sentinel: the chip row renders
         * empty (no fake "chemrxiv" category), the FTS mirror indexes title/abstract/authors only, and
         * `source = MANUAL` (the acquisition-path axis, distinct from identity origin) passes the embedding
         * gate so the paper becomes semantically searchable like any other.
         */
        suspend fun saveExternalPaper(draft: ExternalPaperDraft): Paper {
            // Cross-source de-dup at the IMPORT seam (PE.2, routed through the atomic DAO chokepoint in PE.3):
            // reuse an already-stored row sharing the normalized DOI instead of forking, and never overwrite it —
            // an import's metadata is thinner (no category/version), so clobbering a richer native row would be a
            // regression (the PFP.1 degraded-metadata lesson).
            val ref = resolvePaperRef(arxivId = null, origin = draft.origin, nativeId = draft.nativeId)
            val paper =
                Paper(
                    ref = ref,
                    latestVersion = 1,
                    title = draft.title,
                    abstract = draft.abstract,
                    publishedAt = draft.publishedAt,
                    updatedAt = draft.publishedAt,
                    primaryCategory = "",
                    categories = emptyList(),
                    authors = draft.authors,
                    doi = draft.nativeId,
                    pdfUrl = draft.pdfUrl,
                    source = PaperSource.MANUAL,
                )
            val id = paperDao.insertPaperIfAbsentWithRelations(paper.toEntity(), paper.authors, paper.categories)
            return checkNotNull(paperDao.paperById(id)).toListDomain()
        }

        private suspend fun fetchAndCache(
            query: ArxivQuery,
            source: PaperSource,
        ): AppResult<PaperPage> =
            arxivApiClient.fetch(query, source).map { feed ->
                cache(feed)
                PaperPage(
                    papers = feed.papers,
                    totalResults = feed.totalResults,
                    nextStart =
                        (query.start + feed.papers.size)
                            .takeIf { feed.papers.isNotEmpty() && it < feed.totalResults },
                )
            }

        private suspend fun cache(feed: ArxivFeed) {
            feed.papers.forEach { paper ->
                paperDao.upsertPaperWithRelations(paper.toEntity(), paper.authors, paper.categories)
            }
        }

        companion object {
            private const val CACHED_PAGE = 50
            private const val EXTERNAL_PAGE_SIZE = 25

            // P-OA: the crosswalk scans OpenAlex's top-N relevance-ranked title hits; 8 keeps the published
            // sibling in-window (verified) while the title+author gates keep precision. The timeout budget must
            // exceed OpenAlex's own ≥1.2s self-spacing mutex plus a slow response, else a queued call reads as
            // a spurious offline error.
            private const val OA_SCAN_LIMIT = OaFulltextResolver.DEFAULT_CAP
            private const val OA_TIMEOUT_MS = 9_000L
        }
    }
