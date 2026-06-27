package dev.blokz.arxiver.core.network

/**
 * The egress allowlist for the app's network red line (SPEC-P-HTML §7, CLAUDE.md "Network calls only
 * to …"). Every host the app is permitted to reach over HTTPS, lowercased and canonical. Enforced by
 * [AllowedHostsInterceptor] on the dedicated arXiv-group client; anything not listed is rejected
 * before a socket opens.
 *
 * Note: a host being listed here does **not** route its traffic through the rate limiter — only the
 * `@ArxivClient` consumers (Atom API, PDF, and the future HTML/image fetchers) are gated + spaced;
 * the AI providers / routine trigger / model downloaders stay on the bare shared client. `huggingface.co`
 * is listed for the pinned model download, and `api.semanticscholar.org` for the citation graph, but
 * both currently run on the bare client (the interceptor never fires for them) — they are here so that
 * if they ever move to the gated client the allowlist already permits them.
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
            // citation graph
            "api.semanticscholar.org",
            // the pinned model-download host
            "huggingface.co",
        )

    fun isAllowed(host: String?): Boolean = host != null && host.lowercase() in ALLOWED
}
