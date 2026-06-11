package dev.blokz.arxiver.core.network.arxiv

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.model.PaperSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Client for the arXiv Atom API (ARCHITECTURE §3.1). All requests flow through
 * the shared [ArxivRateLimiter]; 5xx responses retry with exponential backoff.
 */
class ArxivApiClient(
    private val httpClient: OkHttpClient,
    private val rateLimiter: ArxivRateLimiter,
    private val dispatchers: DispatcherProvider,
    private val parser: AtomFeedParser = AtomFeedParser(),
    baseUrl: String = DEFAULT_BASE_URL,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val retryDelaysMs: List<Long> = listOf(2_000, 4_000, 8_000),
) {
    private val baseHttpUrl: HttpUrl = baseUrl.toHttpUrl()

    suspend fun fetch(
        query: ArxivQuery,
        source: PaperSource = PaperSource.SEARCH,
    ): AppResult<ArxivFeed> =
        withContext(dispatchers.io) {
            val url =
                baseHttpUrl.newBuilder().apply {
                    query.toQueryParameters().forEach { (k, v) -> addQueryParameter(k, v) }
                }.build()
            val request =
                Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .build()
            execute(request, source)
        }

    private suspend fun execute(
        request: Request,
        source: PaperSource,
    ): AppResult<ArxivFeed> {
        var attempt = 0
        while (true) {
            rateLimiter.acquire()
            val outcome =
                runCatching {
                    httpClient.newCall(request).execute().use { response ->
                        when {
                            response.isSuccessful -> {
                                val body = response.body ?: throw IOException("Empty body")
                                AppResult.Success(parser.parse(body.byteStream(), source))
                            }
                            response.code in RETRYABLE_CODES -> Retry(response.code)
                            else -> AppResult.Failure(AppError.Upstream(response.code))
                        }
                    }
                }.getOrElse { e ->
                    if (e is IOException) Retry(null) else AppResult.Failure(AppError.Unexpected(e))
                }

            when (outcome) {
                is Retry -> {
                    if (attempt >= retryDelaysMs.size) {
                        return AppResult.Failure(
                            outcome.code?.let { AppError.Upstream(it) } ?: AppError.Offline,
                        )
                    }
                    delay(retryDelaysMs[attempt])
                    attempt++
                }
                is AppResult.Success<*> ->
                    @Suppress("UNCHECKED_CAST")
                    return outcome as AppResult<ArxivFeed>
                is AppResult.Failure -> return outcome
            }
        }
    }

    private data class Retry(val code: Int?)

    companion object {
        const val DEFAULT_BASE_URL = "https://export.arxiv.org/api/query"
        const val DEFAULT_USER_AGENT = "Arxiver/0.1 (https://github.com/blokzdev/arxiver)"
        private val RETRYABLE_CODES = setOf(429, 500, 502, 503, 504)
    }
}
