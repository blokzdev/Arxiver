package dev.blokz.arxiver.core.network.pdf

import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Per-host spacing + identity policy for the shared `@ArxivClient` PDF path (SPEC-P-SOURCES §7, PS.1).
 *
 * A PDF now comes from either the arXiv group (`arxiv.org`) OR a non-arXiv source (chemRxiv, PS.1). These
 * must NOT share one spacer: the arXiv group rides the ≥3s red-line limiter (FIFO-serialized app-wide with
 * the Atom + HTML fetchers), while non-arXiv hosts self-space on a separate, more lenient politeness slot.
 * Keying on the URL **host** (not a caller-passed source enum) means this can never desync from the
 * [dev.blokz.arxiver.core.network.AllowedHostsInterceptor] (also host-gated), and a caller can't pass the
 * wrong source for an `arxiv.org` URL to dodge the ≥3s line.
 *
 * RED LINE: [arxivLimiter] MUST be the SAME singleton the Atom/HTML fetchers hold, else arXiv requests stop
 * serializing against them and the global ≥3s spacing silently breaks. Two guards: `PdfHostPolicyTest`
 * asserts this class returns the exact instance it was constructed with (reference identity, not spacing),
 * and the `:app` `PdfLimiterWiringTest` asserts the `pdfDownloader` DI provider passes the injected
 * singleton (not a fresh `ArxivRateLimiter()`) into [arxivLimiter].
 */
class PdfHostPolicy(
    private val arxivLimiter: ArxivRateLimiter,
    private val politeLimiter: ArxivRateLimiter,
) {
    /** The spacing slot for [url]'s host: the ≥3s arXiv singleton for the arXiv group, else the polite one. */
    fun limiterFor(url: String): ArxivRateLimiter = if (isArxivGroupHost(url)) arxivLimiter else politeLimiter

    /** One app-identifying User-Agent for every host (polite everywhere; arXiv etiquette by default). */
    fun userAgentFor(url: String): String = ArxivApiClient.DEFAULT_USER_AGENT

    // A null/unparseable host defaults to the arXiv limiter (conservative: never UNDER-space the red line).
    private fun isArxivGroupHost(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return true
        return host in ARXIV_GROUP_HOSTS
    }

    companion object {
        /** The ≥3s-spaced arXiv-group hosts (mirrors the arXiv subset of `AllowedHosts.ALLOWED`). */
        val ARXIV_GROUP_HOSTS: Set<String> = setOf("arxiv.org", "export.arxiv.org", "ar5iv.labs.arxiv.org")
    }
}
