package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.map
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.database.toDomain
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.PaperSource
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivFeed
import dev.blokz.arxiver.core.network.arxiv.ArxivQuery
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

        /** Online arXiv search (field prefixes and booleans pass through). */
        suspend fun searchArxiv(
            query: String,
            start: Int = 0,
        ): AppResult<PaperPage> = fetchAndCache(ArxivQuery.search(query, start = start), PaperSource.SEARCH)

        /**
         * Paper for the detail screen: local cache first, then network refresh.
         * Returns null only when the paper is unknown locally AND unfetchable.
         */
        suspend fun paper(id: ArxivId): Paper? {
            paperDao.paperWithRelations(id.value)?.let { return it.toDomain() }
            val fetched = fetchAndCache(ArxivQuery.byIds(listOf(id.value)), PaperSource.SHARE_IN)
            return (fetched as? AppResult.Success)?.value?.papers?.firstOrNull()
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
    }
