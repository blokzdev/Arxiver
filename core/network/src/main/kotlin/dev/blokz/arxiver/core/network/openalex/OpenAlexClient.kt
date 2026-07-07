package dev.blokz.arxiver.core.network.openalex

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.model.Source
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** The `/works` list envelope. `meta.next_cursor` drives cursor pagination (first page uses `cursor=*`). */
@Serializable
data class OpenAlexResponse(
    val meta: OpenAlexMeta = OpenAlexMeta(),
    val results: List<OpenAlexWork> = emptyList(),
)

@Serializable
data class OpenAlexMeta(
    val count: Int? = null,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/**
 * A single OpenAlex work. Several fields arrive URL-prefixed (`id`/`doi`/`source.id`) and the abstract is an
 * **inverted index** (`word -> [positions]`), not plain text — the helpers below normalize both. `ignoreUnknownKeys`
 * drops the many fields we don't model; a renamed field degrades to null/empty, never a crash.
 */
@Serializable
data class OpenAlexWork(
    val id: String? = null,
    val doi: String? = null,
    val title: String? = null,
    @SerialName("publication_date") val publicationDate: String? = null,
    @SerialName("abstract_inverted_index") val abstractInvertedIndex: Map<String, List<Int>>? = null,
    val authorships: List<OpenAlexAuthorship> = emptyList(),
    @SerialName("primary_location") val primaryLocation: OpenAlexLocation? = null,
    @SerialName("best_oa_location") val bestOaLocation: OpenAlexLocation? = null,
    // All locations (arXiv + institutional + journal). Already in the default browse payload — no `select` needed;
    // `ignoreUnknownKeys` was silently dropping it. Carries the arXiv cross-id for de-dup (P-FeedPolish).
    val locations: List<OpenAlexLocation> = emptyList(),
    @SerialName("open_access") val openAccess: OpenAlexOpenAccess? = null,
    @SerialName("primary_topic") val primaryTopic: OpenAlexTopic? = null,
) {
    /** Reconstruct the plain-text abstract from OpenAlex's inverted index. Null when absent/empty. */
    fun abstractText(): String? {
        val idx = abstractInvertedIndex ?: return null
        val max = idx.values.flatMap { it }.maxOrNull() ?: return null
        val slots = arrayOfNulls<String>(max + 1)
        idx.forEach { (word, positions) -> positions.forEach { p -> if (p in slots.indices) slots[p] = word } }
        return slots.filterNotNull().joinToString(" ").ifBlank { null }
    }

    /** Bare DOI (strip the `https://doi.org/` prefix, lowercased by OpenAlex) — null if blank/absent. */
    fun bareDoi(): String? = doi?.removePrefix("https://doi.org/")?.takeIf { it.isNotBlank() }

    /** The primary source's OpenAlex id (`https://openalex.org/S123` -> `S123`); null if not an `S…` id. */
    fun sourceId(): String? = primaryLocation?.source?.id?.substringAfterLast('/')?.takeIf { it.startsWith("S") }

    /** The best directly-usable OA PDF url, if OpenAlex has one (often null → the hit is read-only). */
    fun oaPdfUrl(): String? = bestOaLocation?.pdfUrl ?: primaryLocation?.pdfUrl

    /**
     * The arXiv cross-id landing URL this work carries in [locations] (P-FeedPolish de-dup), preferring the
     * parse-safe `arxiv.org/abs|pdf` form (the `doi.org/10.48550/arxiv.` form doesn't parse → skipped). A
     * chemRxiv-*primary* work CAN carry an arXiv location, so this is the cross-source fork key; null if none.
     */
    fun arxivLandingUrl(): String? {
        val arxivLocations = locations.filter { it.source?.id?.substringAfterLast('/') == OpenAlexClient.SID_ARXIV }
        return arxivLocations.firstNotNullOfOrNull { it.landingPageUrl?.takeIf { u -> "arxiv.org/" in u } }
            ?: arxivLocations.firstNotNullOfOrNull { it.landingPageUrl }
    }

    fun authorNames(): List<String> = authorships.mapNotNull { it.author?.displayName?.takeIf { n -> n.isNotBlank() } }
}

@Serializable
data class OpenAlexAuthorship(val author: OpenAlexAuthor? = null)

@Serializable
data class OpenAlexAuthor(
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class OpenAlexLocation(
    @SerialName("pdf_url") val pdfUrl: String? = null,
    @SerialName("landing_page_url") val landingPageUrl: String? = null,
    val source: OpenAlexSource? = null,
)

@Serializable
data class OpenAlexSource(
    val id: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val type: String? = null,
)

@Serializable
data class OpenAlexOpenAccess(
    @SerialName("oa_status") val oaStatus: String? = null,
    @SerialName("oa_url") val oaUrl: String? = null,
)

@Serializable
data class OpenAlexTopic(val field: OpenAlexTopicField? = null)

@Serializable
data class OpenAlexTopicField(
    @SerialName("display_name") val displayName: String? = null,
)

/**
 * OpenAlex `/works` client (P-Feeds). The un-gated aggregator that serves the sources we can't reach natively
 * (chemRxiv — Cloudflare-dead direct — and new preprint servers). Runs on the `@ArxivClient` host-gated client
 * (egress-allowlisted to `api.openalex.org`) and self-spaces ≥1.2s on its own [mutex] (NOT the ≥3s arXiv
 * limiter). Free "polite pool" access via the [mailto] query param; an optional [apiKey] (BYOK, evaluated
 * per-request so a key entered later is honored) is sent as `?api_key=` for the prepaid tier. Errors map to the
 * shared [AppResult]: non-2xx → `Upstream(code)` (incl. a 429/401), `IOException` → `Offline`.
 */
class OpenAlexClient(
    private val httpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
    private val mailto: String,
    private val apiKey: () -> String? = { null },
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val minSpacingMs: Long = MIN_SPACING_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var lastRequestAtMs = Long.MIN_VALUE / 2

    /** Full-text search across works, optionally restricted to one OpenAlex source id. */
    suspend fun search(
        query: String,
        limit: Int,
        sourceId: String? = null,
    ): AppResult<OpenAlexResponse> =
        request { b ->
            b.addQueryParameter("search", query)
            b.addQueryParameter("per-page", limit.toString())
            sourceId?.let { b.addQueryParameter("filter", "primary_location.source.id:$it") }
        }

    /**
     * Browse a source's newest works published on/after [sinceIso] (`YYYY-MM-DD`) — the follow primitive.
     * Cursor-paginated: first call passes `cursor="*"`, then feed back [OpenAlexMeta.nextCursor] until null.
     */
    suspend fun browse(
        sourceId: String,
        sinceIso: String,
        cursor: String = "*",
        category: String? = null,
        perPage: Int = PAGE_SIZE,
    ): AppResult<OpenAlexResponse> =
        request { b ->
            val filter =
                buildString {
                    append("primary_location.source.id:").append(sourceId)
                    append(",from_publication_date:").append(sinceIso)
                    // An OpenAlex Field ("fields/N", PF.3) narrows the source to a discipline. A wrong/stale id
                    // fails SAFE (HTTP 200, meta.count=0 — live-verified), never an error; only append when
                    // non-blank so the whole-source browse stays byte-identical (2-clause).
                    category?.takeIf { it.isNotBlank() }?.let { append(",primary_topic.field.id:").append(it) }
                }
            b.addQueryParameter("filter", filter)
            b.addQueryParameter("sort", "publication_date:desc")
            b.addQueryParameter("per-page", perPage.toString())
            b.addQueryParameter("cursor", cursor)
        }

    private suspend fun request(build: (HttpUrl.Builder) -> Unit): AppResult<OpenAlexResponse> =
        withContext(dispatchers.io) {
            space()
            val url =
                "$baseUrl/works".toHttpUrl().newBuilder()
                    .apply(build)
                    // `mailto` claims the free polite pool; `api_key` (when set) upgrades to the prepaid tier.
                    .addQueryParameter("mailto", mailto)
                    .apply { apiKey()?.let { addQueryParameter("api_key", it) } }
                    .build()
            execute(Request.Builder().url(url).build()) { json.decodeFromString<OpenAlexResponse>(it) }
        }

    /** Claim a ≥1.2s-spaced slot (the sole politeness spacer for this host). */
    private suspend fun space() =
        mutex.withLock {
            val wait = lastRequestAtMs + minSpacingMs - nowMs()
            if (wait > 0) delay(wait)
            lastRequestAtMs = nowMs()
        }

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
        const val DEFAULT_BASE_URL = "https://api.openalex.org"
        private const val MIN_SPACING_MS = 1_200L
        private const val PAGE_SIZE = 50

        // OpenAlex source ids (type=repository), live-verified 2026-07. Keyed by our Source wire token so the
        // engine can map a source both ways without a second lookup.
        const val SID_ARXIV = "S4306400194"
        const val SID_BIORXIV = "S4306402567"
        const val SID_MEDRXIV = "S3005729997"
        const val SID_CHEMRXIV = "S4393918830"
        const val SID_RESEARCH_SQUARE = "S4306525896"
        const val SID_PREPRINTS_ORG = "S6309402219"
        const val SID_SSRN = "S4210172589"
        const val SID_PSYARXIV = "S4306401687"

        /**
         * A [Source] → its OpenAlex source id, for the OpenAlex-served sources (chemRxiv + the PF.3 new
         * sources). `null` = not OpenAlex-served (arXiv/bio/med ride native backends; S2 is not a feed).
         * Exhaustive with NO `else` → adding a [Source] compile-forces a decision here, so a new
         * OpenAlex-served source can never silently resolve to `null` (the old `AppModule.sidFor` bug).
         */
        fun sidFor(source: Source): String? =
            when (source) {
                Source.CHEMRXIV -> SID_CHEMRXIV
                Source.RESEARCH_SQUARE -> SID_RESEARCH_SQUARE
                Source.SSRN -> SID_SSRN
                Source.PREPRINTS_ORG -> SID_PREPRINTS_ORG
                Source.PSYARXIV -> SID_PSYARXIV
                Source.ARXIV, Source.BIORXIV, Source.MEDRXIV, Source.S2 -> null
            }
    }
}
