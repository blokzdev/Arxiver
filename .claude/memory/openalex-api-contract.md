---
name: openalex-api-contract
description: OpenAlex /works API — endpoints, source ids, cursor, inverted-index abstract, 2026 metering + BYOK api_key
type: reference
---

OpenAlex (`https://api.openalex.org`) is un-gated (no Cloudflare) and is Arxiver's discovery backend for the
sources we can't reach natively (chemRxiv, new preprint servers) — see [[chemrxiv-cloudflare-blocked]]. Live-verified 2026-07.

- **Search:** `GET /works?search=<q>&per-page=N&filter=primary_location.source.id:<SID>&mailto=<email>`
- **Browse (follow primitive):** `GET /works?filter=primary_location.source.id:<SID>,from_publication_date:<YYYY-MM-DD>&sort=publication_date:desc&per-page=N&cursor=<c>`
- **Cursor pagination:** first page `cursor=*`, then `meta.next_cursor` until null.
- **Source ids (type=repository):** arXiv `S4306400194`, bioRxiv `S4306402567`, medRxiv `S3005729997`,
  chemRxiv `S4393918830`, Research Square `S4306525896`, Preprints.org `S6309402219`, SSRN `S4210172589`,
  PsyArXiv `S4306401687`.
- **Normalization quirks (Arxiver's `OpenAlexClient` handles):** `id`/`doi`/`source.id` are URL-prefixed
  (`https://doi.org/…`, `https://openalex.org/S…` — strip); the abstract is an **`abstract_inverted_index`**
  (`{word:[positions]}`) that must be reconstructed to plain text; `best_oa_location.pdf_url` is often null →
  the work is read-only (bioRxiv OA-pdf coverage via OpenAlex ~16%, chemRxiv ~64%).
- **Metering (NEW in 2026):** `mailto=<email>` grants the free "polite pool"; response headers
  `X-RateLimit-Limit:1000` credits per ~20h window, a list/filter req = 1 credit ($0.0001), a full-text search =
  10 credits ($0.001). Arxiver is local-first → each **device** gets its own free budget (ample for a personal
  reader). **BYOK: `?api_key=<key>`** (a dummy → `401 "Invalid or missing API key"`) upgrades to the prepaid tier.

- **Category = OpenAlex Field filter (PF.3, live-verified 2026-07-06):** the filter key is
  **`primary_topic.field.id:fields/<N>`** — the response `meta` echoes it as OQL `field is (fields/<N>)`.
  There are **26 top-level Fields** (`GET /fields`, stable ids). Ids are NON-obvious — verify, don't guess:
  Chemistry=**16** (NOT 23), Environmental Science=23, Computer Science=17, Mathematics=26, Physics&Astronomy=31,
  Biochemistry/Genetics/MolBio=13, Neuroscience=28, Psychology=32, Economics/Econometrics/Finance=20,
  Medicine=27, Materials Science=25, Engineering=22, Social Sciences=33, Immunology&Microbiology=24,
  Chemical Engineering=15, Earth&Planetary=19, Agricultural&Biological=11, Pharmacology/Tox=30, Energy=21,
  Business/Mgmt/Accounting=14, Arts&Humanities=12, Decision Sciences=18, Health Professions=36, Nursing=29,
  Dentistry=35, Veterinary=34. (Full table via `/fields?per-page=30`.)
- **Fails safe:** an unknown/wrong field id (e.g. `fields/999`) returns **HTTP 200, meta.count=0, `results:[]`** —
  never an error → a stale id degrades to an empty feed, not a crash. A missing category clause = the whole source.
- **Field spread is real per source (group_by `primary_topic.field.id`):** chemRxiv/Chemistry(16) = ~15.8k of ~62k;
  **SSRN spans all 26 Fields** — NOT a single-field source, so a per-source Field picker + a "whole source (no
  filter)" escape hatch both matter for OpenAlex-backed follows.
- **ALL-TIME vs RECENT distributions differ sharply — always window the census (measured 2026-07-10).** The
  all-time SSRN reading (top Social Sciences ~19%) is a TRAP: filtered to `from_publication_date:2024-01-01`,
  SSRN's #1 Field is **Engineering 21.87%** (Social Sciences 11.57%, Economics 7.75%) — post-Elsevier "Preprints
  with SSRN" made it a broad STEM firehose. A follow feed shows *recent* works, so curate on the recent window,
  never all-time. Recent (2024+) leaders: chemRxiv → Chemistry 24.3 / Materials 19.4 / Engineering 15.5 (CompSci
  4.7 and Medicine 3.9 are real, not noise); PsyArXiv → Psychology 40.3 / Neuroscience 20.9 / Medicine 9.0.
- **`has_abstract` is a LIAR — never gate on it.** It reports `true` even when the response omits
  `abstract_inverted_index` (it means OpenAlex holds the abstract *internally*, before a licensing strip). Measured
  presence of the actual field in the body: SSRN **0%**, Research Square **14%** (a permanent licensing strip, not
  an ingest lag), chemRxiv 99.5%, bio/med + Preprints.org 100%. Judge abstract presence from the body only.
- **PsyArXiv (OSF-hosted) breaks three assumptions.** (1) **98.97% DOI-null** (5,498/5,555) — any code doing
  `bareDoi() ?: return null` silently discards it, and any DOI-keyed de-dup can never match it. chemRxiv is 0.00%
  DOI-null, so this is OSF-specific. (2) ~100% `pdf_url`-null → identity/link must come from the OpenAlex work id +
  `landing_page_url`. (3) **`publication_date` is a re-harvest artifact** (99% of works dated 2025), so
  `from_publication_date` windowing misbehaves — prefer `from_created_date` for OSF sources. SSRN is ~16% DOI-null.
- **The arXiv `locations[]` crosswalk is real but near-inert: 6 of 61,795 chemRxiv works (0.0097%), measured
  2026-07-10.** The "a chemRxiv-primary work CAN carry an arXiv location" claim below is TRUE and stands — but do
  NOT infer that it carries cross-source de-dup. OpenAlex keeps a cross-post as **two separate works with different
  DOIs** (`10.48550/arXiv.*` vs `10.26434/chemrxiv-*`), so neither the crosswalk nor DOI-matching catches the
  common arXiv↔chemRxiv cross-post; that needs title normalization. The crosswalk code is still correct and cheap.
- **Metering caveat for a picker:** a browse/filter req = 1 credit, so populate the Field list from the **hardcoded
  26-Field table above (0 credits)** — never a live `/fields` fetch per picker-open.

- **arXiv cross-id is NOT in `ids` (live-verified 2026-07-07):** an OpenAlex work exposes **no `ids.arxiv` key** —
  `ids` on a modern work carries only `openalex` (+ sometimes `mag`/`doi`). The arXiv identity lives ONLY in
  **`locations[]`**: an entry with `source.id == S4306400194` (arXiv) whose `landing_page_url` is
  `http(s)://arxiv.org/abs/<id>` (or `/pdf/<id>`, or `https://doi.org/10.48550/arxiv.<id>`). A single work often
  has **multiple `locations`** across sources (arXiv + institutional repo + journal), and `primary_location` may
  be a preprint server while an arXiv location coexists — so a chemRxiv-primary work CAN carry an arXiv location
  (the cross-source fork case). To crosswalk at ingest: model `locations[].{landing_page_url, source.id}`, pick the
  arXiv-source entry, and feed its URL straight to `ArxivId.parse` (which already accepts a `arxiv.org/abs/…` URL)
  → `resolvePaperRef`. The browse must `select` (or just model) `locations`; `ignoreUnknownKeys` silently drops it
  otherwise. This is data already in the browse response — **no extra fetch, no rate-budget cost.**

api.biorxiv.org (native bio/med, un-gated) is the OTHER backend — contract in `docs/SPEC-P-FEEDS.md` §3
(server-side `?category=`, verified). Governed by SPEC-P-FEEDS.
