---
name: chemrxiv-cloudflare-blocked
description: chemRxiv's direct API is Cloudflare-blocked for scripted clients; its PDF is Atypon cookie-walled — reach it via OpenAlex, read via external-open
type: gotcha
---

**chemRxiv's direct API (`chemrxiv.org/engage/chemrxiv/public-api/v1/items`) is Cloudflare fingerprint-gated for
scripted clients** — verified 2026-07 from a residential IP: a browser UA → the "Just a moment…" 403 challenge;
the app's `Arxiver/0.1` UA → a synthetic `404 "Missing resource"` even for known-good endpoints; the app's real
**OkHttp** client gets the same 404. The endpoint path is CORRECT (matches paperscraper/mlederbauer/chemrxiv-dashboard) —
it's a CF gate, not a code bug. So **the shipped `search_chemrxiv` (PT.4) does not work in production**, and no
wrapper anywhere beat the gate (chemrxiv-dashboard's cron died ~2025-03; mlederbauer is "deprecated due to API
change"; paperscraper ripped out direct access for a Crossref fallback, Feb 2026). **Do NOT attempt
UA-spoofing/cloudscraper** — a fragile arms race nobody won.

**Fix (P-Feeds):** reach chemRxiv **discovery/metadata via OpenAlex** ([[openalex-api-contract]], un-gated) — it
re-serves chemRxiv works with abstract + a PDF url. `ChemRxivClient` stays in-tree but unwired (documented CF-dead).

**chemRxiv PDF is Atypon cookie-walled too:** the OpenAlex-supplied asset url
(`chemrxiv.org/engage/api-gateway/chemrxiv/assets/…/original/*.pdf`) 301-chains to `chemrxiv.org/action/cookieAbsent`
(an HTML "enable cookies" page, not a PDF). So chemRxiv in-app PDF **degrades to open-in-browser** — the PS.2 `%PDF`
magic-byte guard in `PdfDownloader` rejects the HTML and the reader offers the external-open fallback. Don't
overclaim in-app chemRxiv PDF. (`AllowedHosts` still lists `chemrxiv.org` — harmless; the fetch just fails closed.)
