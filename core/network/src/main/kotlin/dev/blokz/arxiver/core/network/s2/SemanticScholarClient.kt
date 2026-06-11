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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
data class S2ExternalIds(val ArXiv: String? = null)

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

/**
 * Semantic Scholar Academic Graph client (ARCHITECTURE §3.5, SPEC-DATA §2).
 * Unauthenticated free tier: requests are spaced >= 1.2s and callers batch
 * nightly. An API key, when configured, lifts politeness pressure but the
 * spacing stays.
 */
class SemanticScholarClient(
    private val httpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val apiKey: String? = null,
    private val minSpacingMs: Long = MIN_SPACING_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var lastRequestAtMs = Long.MIN_VALUE / 2

    suspend fun paperByArxivId(arxivId: String): AppResult<S2Paper> =
        withContext(dispatchers.io) {
            mutex.withLock {
                val wait = lastRequestAtMs + minSpacingMs - nowMs()
                if (wait > 0) delay(wait)
                lastRequestAtMs = nowMs()
            }
            val url = "$baseUrl/graph/v1/paper/arXiv:$arxivId?fields=$FIELDS"
            val request =
                Request.Builder()
                    .url(url)
                    .apply { apiKey?.let { header("x-api-key", it) } }
                    .build()
            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> {
                            val body = response.body?.string() ?: throw IOException("empty body")
                            AppResult.Success(json.decodeFromString<S2Paper>(body))
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
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.semanticscholar.org"
        private const val MIN_SPACING_MS = 1_200L
        private const val FIELDS =
            "paperId,citationCount,references.externalIds,references.title,citations.externalIds,citations.title"
    }
}
