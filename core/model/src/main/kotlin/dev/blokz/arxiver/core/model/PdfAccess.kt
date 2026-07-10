package dev.blokz.arxiver.core.model

/**
 * How a source's PDF can be read (P-Explorer PE.0). A *capability* statement, not an enforcement point: the
 * egress allowlist (`AllowedHostsInterceptor`, per-hop) and `PdfDownloader`'s `%PDF` magic-byte guard remain the
 * enforcement, so an [IN_APP] source whose URL turns out to be un-reachable still fails closed and degrades to
 * open-in-browser rather than rendering a challenge page.
 */
enum class PdfAccess {
    /** The source's OA PDF is fetchable over an allowlisted host — render it in-app. */
    IN_APP,

    /** Gated (cookie-wall / edge-deny / shared-CDN-only) or ToS-restricted — open the landing page in the browser. */
    BROWSER,
}

/**
 * The PDF-access tier of a [Source] — evidence-backed (P-Explorer PE.2h host probe, 2026-07-10), NOT a guess.
 *
 * - **arXiv / bioRxiv / medRxiv** serve their OA PDF over an already-allowlisted host with no off-host redirect
 *   (bio/med verified: `200`, 0 redirects, `application/pdf`, body starts `%PDF`).
 * - **chemRxiv** sits behind an Atypon cookie-wall reached via an un-allowlisted OIDC hop, and the shared PDF
 *   client holds no `CookieJar` → the fetch correctly lands on an HTML challenge that the `%PDF` guard rejects.
 * - **SSRN** (Cloudflare challenge; its ToS bans automated queries) and **Preprints.org** (Akamai edge-deny)
 *   are gated — fetching either would circumvent an access control, so browser-open is the *correct* answer.
 * - **PsyArXiv** resolves only through `storage.googleapis.com`, a multi-tenant CDN whose bucket lives in the URL
 *   *path*; exact-host allowlisting cannot scope it to OSF, so it is deliberately never granted.
 * - **Research Square** is authorized in principle but its shard host is pending an on-device check → BROWSER
 *   until that lands (see `VERIFICATION.md`).
 * - **S2** is a citation/metadata service, never a PDF origin.
 *
 * Exhaustive `when` with no `else`: adding a [Source] compile-forces an explicit decision here. A structural test
 * (`:core:network`) asserts every [IN_APP] source's PDF host is actually in the egress allowlist, so the two can
 * never silently desync.
 */
fun Source.pdfAccess(): PdfAccess =
    when (this) {
        Source.ARXIV, Source.BIORXIV, Source.MEDRXIV -> PdfAccess.IN_APP
        Source.CHEMRXIV,
        Source.RESEARCH_SQUARE,
        Source.SSRN,
        Source.PREPRINTS_ORG,
        Source.PSYARXIV,
        Source.S2,
        -> PdfAccess.BROWSER
    }
