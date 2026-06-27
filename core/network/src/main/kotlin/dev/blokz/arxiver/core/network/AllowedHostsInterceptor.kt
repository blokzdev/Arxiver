package dev.blokz.arxiver.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Pure-synchronous egress gate for the dedicated arXiv-group client (SPEC-P-HTML §7). Rejects any
 * request that is not HTTPS or whose host is not in [AllowedHosts] by throwing [IOException] **before**
 * the socket — so it surfaces through the existing `runCatching` in the fetchers as `AppError.Offline`
 * with no new error plumbing.
 *
 * Registered as **both** an application and a network interceptor on the dedicated client:
 * - as an **application** interceptor it runs **before any socket**, so a disallowed *original* host is
 *   rejected with no TCP/TLS connection at all;
 * - as a **network** interceptor it fires on **every redirect hop**, so a Fastly-fronted `arxiv.org`
 *   3xx to a disallowed host is checked and blocked, not silently followed.
 *
 * It does **not** do rate-limit spacing — `ArxivRateLimiter.acquire` is `suspend` and lives at the
 * fetcher seam (an OkHttp interceptor is synchronous). Stateless, so one instance is safe in both slots.
 */
class AllowedHostsInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (url.scheme != "https") {
            throw IOException("Blocked non-https request to ${url.host}")
        }
        if (!AllowedHosts.isAllowed(url.host)) {
            throw IOException("Blocked request to non-allowlisted host ${url.host}")
        }
        return chain.proceed(chain.request())
    }
}
