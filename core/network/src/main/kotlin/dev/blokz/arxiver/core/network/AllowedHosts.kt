package dev.blokz.arxiver.core.network

/**
 * The egress allowlist for the app's network red line (SPEC-P-HTML §7, CLAUDE.md "Network calls only
 * to …"). Every host the app is permitted to reach over HTTPS, lowercased and canonical. Enforced by
 * [AllowedHostsInterceptor] on the dedicated arXiv-group client; anything not listed is rejected
 * before a socket opens.
 *
 * Note: a host being listed here does **not** route its traffic through the rate limiter — the
 * `@ArxivClient` consumers (Atom API, PDF, HTML/image fetchers, and — from P-Tools — the S2 + chemRxiv
 * search clients) are host-gated, but only the arXiv group is ≥3s-spaced; S2 and chemRxiv self-space via
 * their own 1.2s politeness mutexes. `huggingface.co` (the pinned model download) still runs on the bare
 * client — it is here so that if it ever moves to the gated client the allowlist already permits it.
 */
object AllowedHosts {
    val ALLOWED: Set<String> =
        setOf(
            // arXiv Atom API
            "export.arxiv.org",
            // PDFs + the native HTML edition (Fastly-fronted; can 3xx)
            "arxiv.org",
            // the HTML fallback (P-HTML red-line host, user-approved)
            "ar5iv.labs.arxiv.org",
            // citation graph (moved onto the gated client in PT.3)
            "api.semanticscholar.org",
            // chemRxiv (Cambridge Open Engage) search API (P-Tools PT.4 red-line host, user-approved).
            // Exact-match only: an off-host asset CDN sub-domain is deliberately NOT allowlisted.
            "chemrxiv.org",
            // the pinned model-download host
            "huggingface.co",
        )

    fun isAllowed(host: String?): Boolean = host != null && host.lowercase() in ALLOWED
}
