package dev.blokz.arxiver.core.network.pdf

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Streams a PDF to local storage. Downloads happen at most one at a time
 * (callers go through the repository) and write via a temp file so partial
 * downloads never masquerade as complete ones.
 *
 * Routes through [PdfHostPolicy] (P-HTML PH.2 introduced the shared limiter; P-Sources PS.1 made it
 * per-host): an `arxiv.org` PDF claims the ≥3s red-line singleton (FIFO with Atom/HTML), while a
 * non-arXiv source (chemRxiv) self-spaces on the policy's separate polite slot — the limiter is chosen by
 * the URL host, never by the caller. The injected [httpClient] is the `@ArxivClient` egress-gated client
 * (host-allowlisted via [dev.blokz.arxiver.core.network.AllowedHostsInterceptor]).
 */
class PdfDownloader(
    private val httpClient: OkHttpClient,
    private val hostPolicy: PdfHostPolicy,
    private val dispatchers: DispatcherProvider,
) {
    suspend fun download(
        url: String,
        destination: File,
    ): AppResult<File> =
        withContext(dispatchers.io) {
            if (destination.exists() && destination.length() > 0) {
                // Cache hit: serve from disk without a request or a rate-limit slot.
                return@withContext AppResult.Success(destination)
            }
            // Cache miss only: claim the host's spacing slot (≥3s arXiv singleton, else polite) before the net.
            hostPolicy.limiterFor(url).acquire()
            val tmp = File(destination.parentFile, destination.name + ".part")
            runCatching {
                val request =
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", hostPolicy.userAgentFor(url))
                        .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext AppResult.Failure(AppError.Upstream(response.code))
                    }
                    val body = response.body ?: throw IOException("Empty body")
                    destination.parentFile?.mkdirs()
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                    check(tmp.renameTo(destination)) { "rename failed" }
                }
                AppResult.Success(destination)
            }.getOrElse { e ->
                tmp.delete()
                when (e) {
                    is IOException -> AppResult.Failure(AppError.Offline)
                    else -> AppResult.Failure(AppError.Unexpected(e))
                }
            }
        }
}
