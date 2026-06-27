package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** The outcome of resolving a paper's HTML edition (Phase P-HTML PH.3, SPEC-P-HTML §2). */
sealed interface HtmlFetchResult {
    /** Native arXiv HTML, conversion OK. */
    data class Native(val doc: ReaderDocument) : HtmlFetchResult

    /** ar5iv fallback (may be DEGRADED — the last HTML hope; PH.4 shows an honest banner). */
    data class Ar5iv(val doc: ReaderDocument) : HtmlFetchResult

    /** No usable HTML from either source → open the existing PDF viewer (never strand). */
    data object FallbackToPdf : HtmlFetchResult

    /** A network/transport error (offline, non-404 upstream) — distinct from "no HTML exists". */
    data class Error(val error: AppError) : HtmlFetchResult
}

/**
 * Resolves and prepares a paper's HTML edition: native `arxiv.org/html/<id>vN` PRIMARY → on a 404 the
 * bare native (latest) → on 404 / sanitize-fail / transform-fail / **DEGRADED** conversion, the ar5iv
 * fallback → else `FallbackToPdf`. Every attempt claims the shared ≥3s `ArxivRateLimiter` slot, then
 * fetches through the **@ArxivClient** egress-gated client (passed by `:app` DI as a bare [OkHttpClient]
 * — a `:core:ai` class can't annotate with the `:app` qualifier). Returns the **sanitized + transformed**
 * [ReaderDocument]; the caller (PH.4) persists `doc.bodyHtml` once (sanitize-on-download-once) and never
 * stores raw bytes. Lives in `:core:ai` so it composes [HtmlSanitizer] + [HtmlReaderTransform].
 */
open class HtmlFetcher(
    private val httpClient: OkHttpClient,
    private val rateLimiter: ArxivRateLimiter,
    private val dispatchers: DispatcherProvider,
    private val userAgent: String = ArxivApiClient.DEFAULT_USER_AGENT,
) {
    // `open` so a ViewModel test can substitute a fake returning canned HtmlFetchResults.
    open suspend fun fetch(
        id: ArxivId,
        version: Int,
    ): HtmlFetchResult =
        withContext(dispatchers.io) {
            // 1. native versioned; 2. on 404, the bare (latest) native.
            val nativeBody: String? =
                when (val versioned = get(id.htmlUrl(version))) {
                    is Attempt.Ok -> versioned.body
                    is Attempt.Err -> return@withContext HtmlFetchResult.Error(versioned.error)
                    Attempt.NotFound ->
                        when (val bare = get(id.htmlUrl(null))) {
                            is Attempt.Ok -> bare.body
                            is Attempt.Err -> return@withContext HtmlFetchResult.Error(bare.error)
                            Attempt.NotFound -> null
                        }
                }

            // Native exists + converts cleanly → done. Else (404 / sanitize null / transform null /
            // DEGRADED) fall through to ar5iv.
            if (nativeBody != null) {
                val doc = build(nativeBody, id, HtmlSource.NATIVE)
                if (doc != null && doc.fidelity.fidelity == Fidelity.OK) {
                    return@withContext HtmlFetchResult.Native(doc)
                }
            }

            // 3. ar5iv — accept even DEGRADED (the last HTML option before PDF).
            when (val ar5iv = get(AR5IV_BASE + id.value)) {
                is Attempt.Ok ->
                    build(ar5iv.body, id, HtmlSource.AR5IV)
                        ?.let { HtmlFetchResult.Ar5iv(it) }
                        ?: HtmlFetchResult.FallbackToPdf
                Attempt.NotFound -> HtmlFetchResult.FallbackToPdf
                is Attempt.Err -> HtmlFetchResult.Error(ar5iv.error)
            }
        }

    private fun build(
        rawBody: String,
        id: ArxivId,
        source: HtmlSource,
    ): ReaderDocument? = HtmlSanitizer.sanitize(rawBody)?.let { HtmlReaderTransform.transform(it, id, source) }

    /** One rate-limited GET. Distinguishes 404 (no HTML) from a transport error (offline/upstream). */
    private suspend fun get(url: String): Attempt {
        rateLimiter.acquire()
        return runCatching {
            val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Attempt.Ok(response.body?.string().orEmpty())
                    response.code == 404 -> Attempt.NotFound
                    else -> Attempt.Err(AppError.Upstream(response.code))
                }
            }
        }.getOrElse { e ->
            if (e is IOException) Attempt.Err(AppError.Offline) else Attempt.Err(AppError.Unexpected(e))
        }
    }

    private sealed interface Attempt {
        data class Ok(val body: String) : Attempt

        data object NotFound : Attempt

        data class Err(val error: AppError) : Attempt
    }

    private companion object {
        const val AR5IV_BASE = "https://ar5iv.labs.arxiv.org/html/"
    }
}
