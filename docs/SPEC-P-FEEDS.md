# SPEC-P-FEEDS — multi-source discovery engine (in-house, native + OpenAlex plug-in)

Status: **active** (phase approved 2026-07-06). Governs Phase P-Feeds. Read `docs/SPEC-P-SOURCES.md` first — this
builds on its identity/import seam (`PaperRef`/`Source`/`ExternalPaperDraft`/`saveExternalPaper`).

## 1. Purpose & scope

Give Arxiver **cross-source discovery + follows/feeds** and **fix the CF-broken chemRxiv search**, via an
**in-house multi-source engine** where each source uses its *best backend*, with **OpenAlex as one pluggable
backend** — not a wholesale dependency.

- **In scope:** an OpenAlex client (chemRxiv + new sources), a native `api.biorxiv.org` client (bio/med follows),
  a `PreprintBackend` abstraction + registry, `follows.origin` generalization, a source+category follow picker,
  an optional BYOK OpenAlex key.
- **Out of scope:** replacing native arXiv (Atom stays — canonical, fresher, un-metered); rebuilding OpenAlex's
  server-side dedup/OA-resolution/taxonomy; fighting Cloudflare (no UA-spoofing / cloudscraper); new-source **PDF**
  host allowlisting (per-source Co-Founder decisions — read-only until then).

## 2. The engine

`PreprintBackend` (`:core:network`): `search(query, limit): AppResult<List<PreprintHit>>` +
`browse(source, category?, sinceIso, cursor): AppResult<PreprintPage>`, over a normalized `PreprintHit`
(origin `Source`, doi, title, abstract, authors, publishedAt, oaPdfUrl?). Introduced in **PF.2** (rule-of-two —
2 impls). A `PreprintSourceRegistry` maps `Source` → backend + PDF-host policy + category vocabulary.

| Source | Backend | Rationale |
|---|---|---|
| arXiv | native Atom (`ArxivApiClient`) | canonical ids, freshest, un-metered |
| bioRxiv / medRxiv | native `api.biorxiv.org` (`BioRxivApiClient`) | un-gated, fresh, server-side category, un-metered |
| chemRxiv | OpenAlex (`OpenAlexClient`) | native API Cloudflare-dead; OpenAlex is the only path to abstract + PDF url |
| new sources | OpenAlex | no/poor native APIs; uniform reach; promotable to native |

## 3. Source contracts (all live-verified 2026-07)

### OpenAlex (`api.openalex.org`, un-gated) — `OpenAlexClient`
- Search: `GET /works?search=<q>&per-page=N&filter=primary_location.source.id:<SID>&mailto=<email>`.
- Follow/browse: `GET /works?filter=primary_location.source.id:<SID>,from_publication_date:<YYYY-MM-DD>&sort=publication_date:desc&per-page=N&cursor=<c>`.
- Source ids: arXiv `S4306400194`, bioRxiv `S4306402567`, medRxiv `S3005729997`, chemRxiv `S4393918830`,
  Research Square `S4306525896`, Preprints.org `S6309402219`, SSRN `S4210172589`, PsyArXiv `S4306401687`.
- Cursor pagination (`cursor=*` → `meta.next_cursor`, null at end).
- **Category = an OpenAlex Field (PF.3):** append `,primary_topic.field.id:fields/<N>` to the browse filter (only
  when non-blank — the whole-source browse stays byte-identical). There are **26 top-level Fields** (`/fields`,
  stable ids, hardcoded in `OpenAlexFields.kt`); ids are non-obvious (**Chemistry=16, NOT 23** = Environmental
  Science — live-verified). A wrong/stale id **fails SAFE** (HTTP 200, `meta.count=0`, no error) → an empty feed,
  never a crash; the id→name table is pinned by a structural test. SSRN spans all 26 Fields (so a whole-source
  escape hatch matters). Field ids populate the picker from the hardcoded table — **never a live `/fields` fetch**.
- **Normalization quirks (the client handles):** `id`/`doi`/`source.id` are URL-prefixed (strip); the abstract is
  an **`abstract_inverted_index`** (`word → [positions]`), reconstructed to plain text; `best_oa_location.pdf_url`
  is often null → the hit is read-only.
- **Metering (2026):** `mailto` = free "polite pool"; `X-RateLimit-Limit:1000` credits / ~20h window, a list/filter
  req = 1 credit ($0.0001), a full-text search = 10 credits ($0.001). **Per-device** budget (local-first) → ample
  for a personal reader; **BYOK `?api_key=`** upgrades to the prepaid tier (PF.4).

### bioRxiv/medRxiv (`api.biorxiv.org`, un-gated) — `BioRxivApiClient` (PF.2)
- `GET /details/{biorxiv|medrxiv}/{start}/{end}/{cursor}/json?category=<cat>` — **server-side** `?category=`
  (verified: total 1441→263 for neuroscience), 30/page cursor (`messages[0].{count,total}`), per-item
  `doi,title,authors,category,date,version,abstract`. No author/keyword follow. ~27 bio / ~51 med flat categories
  (hardcode; no `/categories` endpoint). Fresh (real-time), un-metered.

## 4. Reading + import

Reuses SPEC-P-SOURCES §3 **verbatim**: a hit → `ExternalPaperDraft` → `resolvePaperRef(arxivId, origin, doi)` →
`saveExternalPaper` (origin-blind). **Importability is host-gated** — `AllowedHosts.isAllowedUrl(oaPdfUrl)` (https +
allowlisted host); else read-only (external-open). arXiv cross-id always wins (no fork). PDF **bytes** still fetch
from each source's own allowlisted host through `PdfDownloader` (per-host limiter, `%PDF` magic-byte guard).

**Feed-ingest de-dup (P-FeedPolish PFP.1), distinct from import.** `FollowSyncWorker` resolves ONE canonical
`PaperRef` per hit *before* storing the paper or the inbox row, so a cross-posted paper never forks: (1) an
OpenAlex work carries its arXiv cross-id in `locations[]` (a chemRxiv-*primary* work CAN carry an arXiv location —
memory `openalex-api-contract`), extracted to `PreprintHit.arxivId` and run through the same `resolvePaperRef`
chokepoint (arXiv wins → the bare arXiv id); (2) else an origin-blind normalized-DOI lookup reuses an
already-stored row (`paperIdByDoi`, arXiv-origin-preferred, case-insensitive); (3) else the source's `ExternalRef`.
A hit collapsing onto an existing arXiv row is **insert-if-absent** (never clobbers the richer native-arXiv row).
**Forward-only:** existing on-device forks are not retroactively merged.

**chemRxiv honesty:** OpenAlex fixes **discovery + metadata**; the chemRxiv **PDF is Atypon cookie-walled** (the
asset url 301-chains to `chemrxiv.org/action/cookieAbsent` HTML, not a PDF), so in-app PDF **degrades to
open-in-browser** — do NOT claim in-app chemRxiv PDF. bioRxiv/medRxiv PDF bytes come from S2's `openAccessPdf.url`
(PS.2 path — OpenAlex bio/med PDF coverage is only ~16%).

## 5. Network & rate-limit contract

New egress hosts (Co-Founder-approved with the phase): `api.openalex.org`, `api.biorxiv.org`. Both on the
`@ArxivClient` host-gated client, each self-spacing ~1.2s on its own mutex — **never** the ≥3s arXiv singleton.
Host-parse stays in `:core:network` (`AllowedHosts.isAllowedUrl`); `data/tool` imports no okhttp. New-source PDF
hosts are **not** allowlisted (read-only) until a per-source decision.

## 6. Follows generalization (PF.2) + origin-aware lifecycle (PF.3)

Additive Room **v7→v8**: `follows.origin` (default `'arxiv'`) + unique index `(type,value)`→`(type,value,origin)`
(new `Migration7To8` + `8.json` + identity-hash/zero-rows test). `FollowSyncWorker.syncFollow` gains an
`origin`-dispatch: arXiv → native Atom, biorxiv/medrxiv → `BioRxivApiClient`, chemRxiv/new → `OpenAlexBackend.browse`.
Inbox is origin-agnostic (no inbox-UI change). No author/keyword follow for non-arXiv (category+date only).

**PF.3 — the follow lifecycle is origin-aware** (the `(type,value,origin)` index demands it):
- `FollowDao.delete(type,value,origin)` (was origin-blind — would delete every source's row); `CategoryRepository`
  writes `origin = source.wire` and, on unfollow, cleans the follow's inbox rows in the same op
  (`InboxDao.deleteByFollowId`) so no row dangles on a dead `follow_id`. *(This means unfollowing also removes that
  feed's already-arrived inbox items — a deliberate behavior change, incl. for arXiv.)*
- `observeFollowedCategoryCodes` is scoped `origin='arxiv'` (feeds the arXiv taxonomy grid Boolean, so a bioRxiv
  follow of the same code doesn't light the arXiv row). Today's "has any follows" reads a NEW origin-agnostic
  `observeEnabledFollowCount()` — a non-arXiv-only follower still gets the inbox + first-sync skeletons, not the
  "you follow nothing" empty state.
- **New sources (additive, no migration; `papers.origin` is TEXT):** `RESEARCH_SQUARE`, `SSRN`, `PREPRINTS_ORG`,
  `PSYARXIV` → `openAlexBackend` (SIDs via the exhaustive `OpenAlexClient.sidFor`, no silent `else→null`).
- **Picker:** a `SourceFollowSheet` (in Explore, an `RssFeed` action) reads the static `PreprintSourceRegistry`
  (zero network on open): pick source → whole-source or a source-appropriate category (bio/med native strings, or
  an OpenAlex Field). Toggle state keys on `(origin,value)`. Whole-source = a `value=""` follow (flows through both
  backends' non-blank category guard). A firehose source short-circuits paging once page 1 fills the first-sync
  limit (credit budget). New-source PDFs stay host-gated → external-open (hosts not allowlisted; per-source
  deferral). **Backup** now carries `follows.origin` (additive-defaulted; NO schema bump — stays
  `arxiver-backup/v2`), so a chemRxiv/bio/med/new-source follow round-trips instead of silently collapsing to arXiv.

## 7. BYOK OpenAlex key (PF.4)

Optional, mirrors the S2 key (PT.3): `ProviderId.OPENALEX` in `AiKeyVault` (EncryptedSharedPreferences), a
per-request `apiKey: () -> String?` supplier → `?api_key=`; free-by-default `mailto`. Settings card, no Test button.
Red line: the key never touches DB/logs/backups/exports/fixtures.

## 8. Red lines (held under the engine)

Every backend host-gated + self-spacing; arXiv ≥3s singleton never serializes a non-arXiv fetch; `data/tool`
okhttp-free (structural test); host-gated importability fails closed; backup carries only public metadata; BYOK
key only in `EncryptedSharedPreferences`; `:core:* ∌ :app`; no telemetry; no destructive migration.

## 9. Subphases & CHECKPOINT

PF.0 client + BYOK plumbing + host · PF.1 fix chemRxiv search via OpenAlex · PF.2 native bio/med backend + engine +
`follows.origin` migration · PF.3 chemRxiv/new-source follows + picker UI · PF.4 BYOK key card · CHECKPOINT
P-Feeds (green build; migration integrity; red-line audit; per-source e2e; device items in VERIFICATION.md).
Committed cut PF.0–PF.2; expansion PF.3–PF.4.

## 10. Decision log
- **R1→OpenAlex-for-chemRxiv:** chemRxiv direct API is CF-dead (verified: curl + app OkHttp both 404; every wrapper
  abandoned it). OpenAlex is the only path. chemRxiv PDF stays external-open (Atypon cookie-wall, verified).
- **Hybrid over pure-OpenAlex:** native for arXiv + bio/med (fresher, un-metered, native categories); OpenAlex only
  where native fails (chemRxiv, API-less new sources). Metering confined to low-volume OpenAlex-backed sources.
- **PF.3 category = OpenAlex Field (not Subfield/Topic):** 26 Fields keep the picker small + the vocab table
  hardcodable (0 credits). Subfield (~250) granularity is a tracked backlog product decision. Ids live-verified;
  a wrong id fails safe (count=0), so the table is structurally pinned.
- **PF.3 unfollow cleans the inbox** (`deleteByFollowId` in the same op): prevents rows dangling on a dead
  `follow_id` as multi-source follows grow. Accepts a rare over-delete when two follows on one source overlap on a
  paper (self-heals on next in-window sync); full per-follow provenance (composite inbox PK) is deferred to backlog.
- **PF.3 whole-source escape hatch** (`value=""`): SSRN/PsyArXiv span many Fields, so forcing a single Field would
  hide most of the source. A firehose short-circuits paging on the page-1 first-sync fill to bound OpenAlex credits.
