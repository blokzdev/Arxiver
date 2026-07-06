package dev.blokz.arxiver.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
            // chemRxiv (Cambridge Open Engage) — search API AND PDF assets, BOTH served from chemrxiv.org
            // (verified 2026-07: real PDFs live at chemrxiv.org/engage/api-gateway/chemrxiv/assets/…, NOT an
            // `assets.chemrxiv.org` sub-domain — an earlier assumption that doesn't resolve). So the PDF read
            // needs no extra host. Exact-match only: any off-host redirect (a CDN sub-domain, S3, …) is still
            // rejected per-hop. (chemRxiv gates downloads behind Cloudflare + an Atypon cookie-wall — a plain
            // fetch may get a 200 HTML challenge, which PdfDownloader rejects → the reader degrades to
            // external-open; device-verify per VERIFICATION §Q-PS4.)
            "chemrxiv.org",
            // bioRxiv / medRxiv PDF hosts (P-Sources PS.2, user-approved). S2 hands back a paper's
            // open-access PDF URL verbatim; a hit is importable-for-in-app-read only when that URL's host
            // is one of these (host-gated, not origin-gated — the OA host and the identity origin are
            // independent). Exact-match: a `doi.org` or arbitrary-publisher OA URL fails closed (read-only,
            // external-open), and an off-host CDN redirect is rejected per hop.
            "www.biorxiv.org",
            "www.medrxiv.org",
            // the pinned model-download host
            "huggingface.co",
        )

    fun isAllowed(host: String?): Boolean = host != null && host.lowercase() in ALLOWED

    /**
     * Host-gate a full URL: true iff its parsed host is allowlisted. A null/malformed/non-HTTP(S) URL
     * yields a null host → false (fail closed). Host extraction lives here in `:core:network` (which
     * already depends on okhttp) so callers in `:app`'s `data/tool` package never import okhttp directly
     * — the `ToolPackageNoOkHttpStructuralTest` red line.
     */
    fun isAllowedUrl(url: String?): Boolean = isAllowed(url?.toHttpUrlOrNull()?.host)
}
