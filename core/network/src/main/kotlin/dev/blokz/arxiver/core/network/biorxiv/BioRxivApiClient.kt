package dev.blokz.arxiver.core.network.biorxiv

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
 * The `details` envelope. `messages[0]` carries the page/status header; `collection[]` the items. Note the
 * API returns `total` as a STRING (e.g. `"263"`) — hence [BioRxivMessage.total] is a `String?`.
 */
@Serializable
data class BioRxivResponse(
    val messages: List<BioRxivMessage> = emptyList(),
    val collection: List<BioRxivItem> = emptyList(),
)

@Serializable
data class BioRxivMessage(
    val status: String? = null,
    val total: String? = null,
    val count: Int? = null,
    val cursor: Int? = null,
)

@Serializable
data class BioRxivItem(
    val doi: String? = null,
    val title: String? = null,
    // A single `;`-separated string ("Wienke, C.; Woller, J. P.") — split by the caller.
    val authors: String? = null,
    val date: String? = null,
    val version: String? = null,
    val category: String? = null,
    val abstract: String? = null,
    val server: String? = null,
    // The journal DOI once peer-reviewed, else "NA" — a "published elsewhere" signal.
    val published: String? = null,
) {
    /** The `;`-separated author string → a clean list. */
    fun authorList(): List<String> = authors?.split(";")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
}

/**
 * bioRxiv/medRxiv native discovery client (P-Feeds PF.2). Both servers are served by the SAME
 * `api.biorxiv.org` host, distinguished by the `{server}` path segment. Un-gated (no Cloudflare), fresh,
 * and — unlike OpenAlex — supports **server-side** `?category=` filtering (live-verified). Runs on the
 * `@ArxivClient` host-gated client (egress-allowlisted to `api.biorxiv.org`) and self-spaces ≥1.2s on its own
 * [mutex] (NOT the ≥3s arXiv limiter). Errors map to [AppResult]: non-2xx → `Upstream(code)`, IO → `Offline`.
 */
class BioRxivApiClient(
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
     * One page of a server's postings in `[start, end]` (both `YYYY-MM-DD`), from [cursor] (0-based offset,
     * PAGE_SIZE per page). [category] filters server-side (lowercase, space-separated, e.g. `"cell biology"`);
     * null/blank = all. The caller advances [cursor] by [BioRxivMessage.count] until it reaches `total`.
     */
    suspend fun details(
        server: String,
        start: String,
        end: String,
        cursor: Int,
        category: String? = null,
    ): AppResult<BioRxivResponse> =
        withContext(dispatchers.io) {
            space()
            val url =
                "$baseUrl/details/$server/$start/$end/$cursor/json".toHttpUrl().newBuilder()
                    .apply { category?.takeIf { it.isNotBlank() }?.let { addQueryParameter("category", it) } }
                    .build()
            execute(Request.Builder().url(url).build()) { json.decodeFromString<BioRxivResponse>(it) }
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
        const val DEFAULT_BASE_URL = "https://api.biorxiv.org"
        private const val MIN_SPACING_MS = 1_200L
        const val PAGE_SIZE = 30

        // The `{server}` path segment for each source.
        const val SERVER_BIORXIV = "biorxiv"
        const val SERVER_MEDRXIV = "medrxiv"

        // Launch dates — the earliest a first-sync window should reach back to (paperscraper's floors).
        const val LAUNCH_BIORXIV = "2013-01-01"
        const val LAUNCH_MEDRXIV = "2019-06-01"
    }
}
