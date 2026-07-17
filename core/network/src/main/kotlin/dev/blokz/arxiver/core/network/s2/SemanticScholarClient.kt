package dev.blokz.arxiver.core.network.s2

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
data class S2ExternalIds(
    val ArXiv: String? = null,
    // Added for search (PT.3): identify the third-party ids a hit carries. Defaulted-nullable, so the
    // existing citation-sync decode (which reads only .ArXiv) is byte-untouched. `CorpusId` is
    // deliberately omitted — it decodes as a numeric, not a String, and would crash the parse.
    val DOI: String? = null,
    val PubMed: String? = null,
)

@Serializable
data class S2LinkedPaper(
    val paperId: String? = null,
    val title: String? = null,
    val externalIds: S2ExternalIds? = null,
)

@Serializable
data class S2Paper(
    val paperId: String? = null,
    val citationCount: Int? = null,
    val references: List<S2LinkedPaper> = emptyList(),
    val citations: List<S2LinkedPaper> = emptyList(),
)

// --- Search DTOs (PT.3 `search_semantic_scholar`). All nullable/defaulted so a partial/renamed
// field never crashes the parse; `Json { ignoreUnknownKeys = true }` drops extras (incl. CorpusId). ---

@Serializable
data class S2Author(
    val authorId: String? = null,
    val name: String? = null,
)

/** S2's LLM-generated one-line summary — an OBJECT `{model,text}`, not a bare string. */
@Serializable
data class S2Tldr(
    val model: String? = null,
    val text: String? = null,
)

@Serializable
data class S2OpenAccessPdf(
    val url: String? = null,
    val status: String? = null,
)

@Serializable
data class S2SearchPaper(
    val paperId: String? = null,
    val title: String? = null,
    val abstract: String? = null,
    val tldr: S2Tldr? = null,
    val openAccessPdf: S2OpenAccessPdf? = null,
    val externalIds: S2ExternalIds? = null,
    val venue: String? = null,
    val year: Int? = null,
    val authors: List<S2Author> = emptyList(),
    val citationCount: Int? = null,
)

/** `/graph/v1/paper/search` envelope. `next` is ABSENT on the last page → nullable. */
@Serializable
data class S2SearchResponse(
    val total: Int? = null,
    val offset: Int? = null,
    val next: Int? = null,
    val data: List<S2SearchPaper> = emptyList(),
)

/** `/recommendations/v1/papers/forpaper/{id}` envelope (P-Discover-MLT PDM.1). */
@Serializable
data class S2RecommendationsResponse(
    val recommendedPapers: List<S2SearchPaper> = emptyList(),
)

/**
 * Semantic Scholar Academic Graph client (ARCHITECTURE §3.5, SPEC-DATA §2, P-Tools PT.3).
 * Free tier works keyless: requests are spaced >= 1.2s via the internal [mutex] and callers batch
 * nightly. An optional user-supplied API key (BYOK), when configured, lifts politeness pressure but
 * the spacing stays. **From PT.3 this runs on the `@ArxivClient` host-gated client** (egress is
 * allowlisted to `api.semanticscholar.org`); the 1.2s mutex is internal and independent of which
 * `OkHttpClient` is injected, so gating adds no ≥3s throttle. [apiKey] is a **supplier** evaluated
 * per-request (mirrors `AnthropicProvider`) so a key entered after this singleton is built is honored.
 */
class SemanticScholarClient(
    private val httpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val apiKey: () -> String? = { null },
    private val minSpacingMs: Long = MIN_SPACING_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var lastRequestAtMs = Long.MIN_VALUE / 2

    suspend fun paperByArxivId(arxivId: String): AppResult<S2Paper> =
        withContext(dispatchers.io) {
            space()
            val url = "$baseUrl/graph/v1/paper/arXiv:$arxivId?fields=$FIELDS"
            val request =
                Request.Builder()
                    .url(url)
                    .apply { apiKey()?.let { header("x-api-key", it) } }
                    .build()
            execute(request) { json.decodeFromString<S2Paper>(it) }
        }

    /**
     * Full-text paper search (`/graph/v1/paper/search`, PT.3 `search_semantic_scholar`). [query] +
     * [venue] are URL-encoded via `HttpUrl`; the year window renders to S2's `year=from-to` filter
     * (open-ended when one bound is null). Shares the 1.2s mutex + the same error mapping as
     * [paperByArxivId] (non-2xx → `Upstream(code)` incl. a 429; `IOException` → `Offline`).
     */
    suspend fun searchPapers(
        query: String,
        limit: Int,
        venue: String? = null,
        yearFrom: Int? = null,
        yearTo: Int? = null,
    ): AppResult<S2SearchResponse> =
        withContext(dispatchers.io) {
            space()
            val url =
                "$baseUrl/graph/v1/paper/search".toHttpUrl().newBuilder()
                    .addQueryParameter("query", query)
                    .addQueryParameter("limit", limit.toString())
                    .addQueryParameter("fields", SEARCH_FIELDS)
                    .apply {
                        venue?.let { addQueryParameter("venue", it) }
                        yearFilter(yearFrom, yearTo)?.let { addQueryParameter("year", it) }
                    }
                    .build()
            val request =
                Request.Builder()
                    .url(url)
                    .apply { apiKey()?.let { header("x-api-key", it) } }
                    .build()
            execute(request) { json.decodeFromString<S2SearchResponse>(it) }
        }

    /**
     * Paper-seeded recommendations (`/recommendations/v1/papers/forpaper/{prefix:id}`, P-Discover-MLT
     * PDM.1) — S2's SPECTER2-embedding KNN over its corpus, backing "Discover more like this". [seedId]
     * is the PREFIXED identifier (`ARXIV:<id>` or `DOI:<doi>`) and is the ONLY thing that leaves the
     * device (no abstract, no derived query). NOTE two traps this method designs out: (1) the base path
     * is `/recommendations/v1/`, NOT `/graph/v1/` — the two S2 APIs share a host but not a router;
     * (2) the seed id is appended as a SINGLE encoded path segment via `HttpUrl.addPathSegment` — a DOI
     * always contains `/`, and the raw-interpolation style of [paperByArxivId] (safe only for arXiv ids)
     * would inject extra path segments. Same fields/mutex/error-map as [searchPapers]; the `from` pool
     * param is deliberately omitted in v1 (endpoint default ≈ recent papers — the UI copy says so).
     */
    suspend fun recommendationsForPaper(
        seedId: String,
        limit: Int,
    ): AppResult<S2RecommendationsResponse> =
        withContext(dispatchers.io) {
            space()
            val url =
                "$baseUrl/recommendations/v1/papers/forpaper".toHttpUrl().newBuilder()
                    .addPathSegment(seedId)
                    .addQueryParameter("limit", limit.toString())
                    .addQueryParameter("fields", SEARCH_FIELDS)
                    .build()
            val request =
                Request.Builder()
                    .url(url)
                    .apply { apiKey()?.let { header("x-api-key", it) } }
                    .build()
            execute(request) { json.decodeFromString<S2RecommendationsResponse>(it) }
        }

    /** Claim a 1.2s-spaced slot (the sole politeness spacer; the caller/worker holds none). */
    private suspend fun space() =
        mutex.withLock {
            val wait = lastRequestAtMs + minSpacingMs - nowMs()
            if (wait > 0) delay(wait)
            lastRequestAtMs = nowMs()
        }

    /** Run [request], decode a 2xx body via [decode]; non-2xx → `Upstream(code)`, IO → `Offline`. */
    private inline fun <T> execute(
        request: Request,
        decode: (String) -> T,
    ): AppResult<T> =
        runCatching {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val body = response.body?.string() ?: throw IOException("empty body")
                        AppResult.Success(decode(body))
                    }
                    else -> AppResult.Failure(AppError.Upstream(response.code))
                }
            }
        }.getOrElse { e ->
            when (e) {
                is IOException -> AppResult.Failure(AppError.Offline)
                else -> AppResult.Failure(AppError.Unexpected(e))
            }
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.semanticscholar.org"
        private const val MIN_SPACING_MS = 1_200L
        private const val FIELDS =
            "paperId,citationCount,references.externalIds,references.title,citations.externalIds,citations.title"
        private const val SEARCH_FIELDS =
            "paperId,title,abstract,tldr,openAccessPdf,externalIds,venue,year,authors,citationCount"

        /** S2's `year` filter: `2019-2023` / `2019-` / `-2023` / `2020`; null when both bounds absent. */
        internal fun yearFilter(
            from: Int?,
            to: Int?,
        ): String? =
            when {
                from != null && to != null -> "$from-$to"
                from != null -> "$from-"
                to != null -> "-$to"
                else -> null
            }
    }
}
