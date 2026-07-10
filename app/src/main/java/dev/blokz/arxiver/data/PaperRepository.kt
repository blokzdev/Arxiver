package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.common.AppResult
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
import dev.blokz.arxiver.core.model.normalizeDoi
import dev.blokz.arxiver.core.model.resolvePaperRef
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivFeed
import dev.blokz.arxiver.core.network.arxiv.ArxivQuery
import dev.blokz.arxiver.core.network.arxiv.SearchFilter
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
    ) {
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
            // Cross-source de-dup at the IMPORT seam (P-Explorer PE.2). PFP.1 taught the *follow* path to reuse an
            // already-stored row sharing a DOI (`FollowSyncWorker.canonicalRef`); this path never learned it, so
            // importing a paper you already hold under another origin FORKED it. Reuse wins, and we deliberately
            // do NOT overwrite the stored row — an import's metadata is thinner (no category/version), so
            // clobbering a richer native row would be a regression (the PFP.1 degraded-metadata lesson).
            normalizeDoi(draft.nativeId)?.let { normalized ->
                paperDao.paperIdByDoi(normalized)?.let { existingId ->
                    paperDao.paperById(existingId)?.let { return it.toListDomain() }
                }
            }
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
            paperDao.upsertPaperWithRelations(paper.toEntity(), paper.authors, paper.categories)
            return paper
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
        }
    }
