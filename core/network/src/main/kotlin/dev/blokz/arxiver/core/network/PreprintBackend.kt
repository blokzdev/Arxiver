package dev.blokz.arxiver.core.network

import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.model.Source

/**
 * One preprint discovered by a backend, normalized across sources. [oaPdfUrl] is the source's own
 * (host-gated) PDF url when one is known — null when the source doesn't supply one (the reader then degrades
 * to external-open). Dates are `YYYY-MM-DD`.
 */
data class PreprintHit(
    val origin: Source,
    val doi: String,
    val title: String,
    val abstract: String,
    val authors: List<String>,
    val publishedIso: String?,
    val oaPdfUrl: String?,
    val version: String? = null,
)

/** A page of [hits] plus an opaque [nextCursor] to fetch the next page (null when exhausted). */
data class PreprintPage(
    val hits: List<PreprintHit>,
    val nextCursor: String?,
)

/**
 * The P-Feeds engine seam: a pluggable per-source discovery backend used by the follow worker. Each source
 * uses its best backend — native `api.biorxiv.org` for bio/med, OpenAlex for chemRxiv + new sources — behind
 * this one interface, so the worker (and any future search surface) is source-agnostic. arXiv keeps its own
 * native Atom path and is NOT a `PreprintBackend`.
 */
interface PreprintBackend {
    /**
     * Newest [source] postings published on/after [sinceIso] (`YYYY-MM-DD`), optionally filtered to
     * [category] (server-side where supported). [cursor] is null on the first page; feed back
     * [PreprintPage.nextCursor] until it is null.
     */
    suspend fun browse(
        source: Source,
        category: String?,
        sinceIso: String,
        cursor: String?,
    ): AppResult<PreprintPage>
}

/**
 * Resolves a follow's [Source] to the backend that serves it. arXiv → null (the worker syncs it via the
 * native Atom path). bio/med → the native client; chemRxiv (+ new sources, PF.3) → OpenAlex.
 */
class PreprintBackendRegistry(
    private val bioRxivBackend: PreprintBackend,
    private val openAlexBackend: PreprintBackend,
) {
    fun backendFor(source: Source): PreprintBackend? =
        when (source) {
            Source.BIORXIV, Source.MEDRXIV -> bioRxivBackend
            Source.CHEMRXIV -> openAlexBackend
            Source.ARXIV, Source.S2 -> null
        }
}
