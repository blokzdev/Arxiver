package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import kotlin.coroutines.coroutineContext

/**
 * Pre-fetches a paper's figure bytes (PH.5, SPEC-P-HTML §7/§8) for [HtmlImageInliner]. Each image is a
 * real arXiv request, so it claims the shared ≥3s [ArxivRateLimiter] slot **before** `newCall` (the red
 * line — this also self-satisfies the `:core:ai` no-bypass structural guard) and rides the **@ArxivClient**
 * egress-gated client passed by DI (https + AllowedHosts only; off the AI-key path by construction).
 *
 * Fetches **serially** (one acquire at a time → FIFO-fair with FollowSync/Embedding on the global slot,
 * never a monopoly), is **cancellable** (the caller wraps it in `withTimeoutOrNull`; cancelling between
 * images stops further acquires), and is **best-effort**: a non-2xx, a non-raster `Content-Type`, an
 * over-[IMAGE_MAX_BYTES] body, or any `IOException` simply omits that image (→ figcaption). It accumulates
 * up to [IMAGE_TOTAL_BYTES_BUDGET] and emits each success via [onImage] **as it lands**, so a deadline
 * cancellation keeps the figures fetched so far (a plain return value would be discarded by the timeout).
 */
open class HtmlImageFetcher(
    private val httpClient: OkHttpClient,
    private val rateLimiter: ArxivRateLimiter,
    private val dispatchers: DispatcherProvider,
    private val userAgent: String = ArxivApiClient.DEFAULT_USER_AGENT,
    // Caps are constructor params (defaulting to the companion values) so a device session can ratify
    // them via DI without a recompile, and so tests can use small bodies. (PROVISIONAL — see HUMAN.md.)
    private val maxImageBytes: Long = IMAGE_MAX_BYTES,
    private val totalBytesBudget: Long = IMAGE_TOTAL_BYTES_BUDGET,
) {
    // `open` so a ViewModel test can substitute a fake returning canned bytes.
    open suspend fun fetchAll(
        images: List<ReaderImage>,
        onImage: (String, InlinedImage) -> Unit = { _, _ -> },
    ): Map<String, InlinedImage> =
        withContext(dispatchers.io) {
            val out = LinkedHashMap<String, InlinedImage>()
            var totalBytes = 0L
            for (image in images) {
                coroutineContext.ensureActive() // promptly honour a deadline/leave-screen cancel
                if (totalBytes >= totalBytesBudget) break
                val (subtype, bytes) = fetchOne(image.fetchUrl) ?: continue
                totalBytes += bytes.size
                val inlined =
                    InlinedImage(
                        mimeSubtype = subtype,
                        base64 = Base64.getEncoder().encodeToString(bytes),
                        transparent = ImageAlpha.hasAlpha(subtype, bytes),
                    )
                out[image.localKey] = inlined
                onImage(image.localKey, inlined)
            }
            out
        }

    /** One rate-limited GET → (raster-subtype, bytes), or null to omit (non-2xx / wrong type / oversize / IO). */
    private suspend fun fetchOne(url: String): Pair<String, ByteArray>? {
        rateLimiter.acquire()
        return runCatching {
            val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val subtype = rasterSubtype(response.header("Content-Type")) ?: return@use null
                val body = response.body ?: return@use null
                if (body.contentLength() > maxImageBytes) return@use null // honour a truthful Content-Length
                val bytes = body.bytes()
                if (bytes.size > maxImageBytes) return@use null
                subtype to bytes
            }
        }.getOrNull()
    }

    /** Map a `Content-Type` to a [SanitizerCore] `DATA_IMAGE`-allowlisted subtype; null rejects (incl. svg+xml). */
    private fun rasterSubtype(contentType: String?): String? =
        when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpeg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> null
        }

    companion object {
        /** Per-image ceiling (pre-base64); observed real arXiv figure PNGs are well under this. */
        const val IMAGE_MAX_BYTES = 600_000L

        /** Running ceiling across a paper so a survey can't bloat index.html beyond a few MB (+~33% base64). */
        const val IMAGE_TOTAL_BYTES_BUDGET = 4_000_000L
    }
}
