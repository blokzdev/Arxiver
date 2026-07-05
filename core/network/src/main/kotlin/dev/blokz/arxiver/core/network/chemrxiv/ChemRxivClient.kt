package dev.blokz.arxiver.core.network.chemrxiv

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

/**
 * `/items` search envelope. **The result nests each paper under `itemHits[].item`** — a flat
 * `data:[...]` DTO would decode to ZERO hits with no error. `next`/paging is via `skip`+`totalCount`.
 */
@Serializable
data class ChemRxivSearchResponse(
    val totalCount: Int? = null,
    val itemHits: List<ChemRxivItemHit> = emptyList(),
)

@Serializable
data class ChemRxivItemHit(val item: ChemRxivItem? = null)

@Serializable
data class ChemRxivItem(
    val id: String? = null,
    val title: String? = null,
    val abstract: String? = null,
    val doi: String? = null,
    val publishedDate: String? = null,
    // The PDF URL is top-level `pdfUrl` when present, else nested under `asset.original.url`.
    val pdfUrl: String? = null,
    val authors: List<ChemRxivAuthor> = emptyList(),
    val asset: ChemRxivAsset? = null,
)

/** An author carries either a combined `name` OR separate `firstName`/`lastName`. */
@Serializable
data class ChemRxivAuthor(
    val firstName: String? = null,
    val lastName: String? = null,
    val name: String? = null,
)

@Serializable
data class ChemRxivAsset(val original: ChemRxivAssetOriginal? = null)

@Serializable
data class ChemRxivAssetOriginal(val url: String? = null)

/**
 * chemRxiv (Cambridge Open Engage) search client (P-Tools PT.4). Keyless public API. Requests are spaced
 * >= 1.2s via the internal [mutex] (its own politeness mutex — NOT the ≥3s `ArxivRateLimiter`), and run
 * on the `@ArxivClient` host-gated client (egress allowlisted to `chemrxiv.org`; an off-host asset CDN
 * redirect is blocked per-hop). The DTO shape mirrors the documented Open Engage envelope
 * (`itemHits[].item`; item fields confirmed against a real wrapper's parse) — `ignoreUnknownKeys` drops
 * extras; a renamed field degrades to a null/empty value, never a crash. A live-GET fixture confirmation
 * rides `VERIFICATION.md` (CI cannot reach the Cloudflare-gated API).
 */
class ChemRxivClient(
    private val httpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val minSpacingMs: Long = MIN_SPACING_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var lastRequestAtMs = Long.MIN_VALUE / 2

    /**
     * Full-text preprint search (`/engage/chemrxiv/public-api/v1/items`). [term] is URL-encoded via
     * `HttpUrl`; [skip] paginates (omitted when 0). Shares the same error mapping as the S2 client
     * (non-2xx → `Upstream(code)` incl. a 429; `IOException` → `Offline`).
     */
    suspend fun searchItems(
        term: String,
        limit: Int,
        skip: Int = 0,
    ): AppResult<ChemRxivSearchResponse> =
        withContext(dispatchers.io) {
            space()
            val url =
                "$baseUrl/engage/chemrxiv/public-api/v1/items".toHttpUrl().newBuilder()
                    .addQueryParameter("term", term)
                    .addQueryParameter("limit", limit.toString())
                    .apply { if (skip > 0) addQueryParameter("skip", skip.toString()) }
                    .build()
            val request = Request.Builder().url(url).build()
            execute(request) { json.decodeFromString<ChemRxivSearchResponse>(it) }
        }

    /** Claim a 1.2s-spaced slot (the sole politeness spacer; there is no rate limiter on this path). */
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
        const val DEFAULT_BASE_URL = "https://chemrxiv.org"
        private const val MIN_SPACING_MS = 1_200L
    }
}
