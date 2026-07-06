# SPEC-P-SOURCES — first-class multi-source papers (Phase P-Sources)

Governs Phase **P-Sources**. Approved by the Co-Founder 2026-07-05 (steer: "approve and include PS.2, take your
time — make the implementations and related surfaces rich and sophisticated"). Planned via an Ultracode pass
(5 mappers + live web/API research → 3 design stances → a 3-lens adversarial judge panel [migration-risk ·
scope/demand · red-line/coherence] → synthesis) + a personal adversarial validation grounding every load-bearing
claim at file:line. This spec is the source of truth; if implementation must deviate, the spec is updated in the
same commit and noted in the Decision log (§9 + ROADMAP).

---

## 1. Purpose & scope

**The gap.** Phase P-Tools gave the chat model the ability to *find* non-arXiv papers (Semantic Scholar, chemRxiv)
mid-conversation, but a hit with no arXiv id is surfaced **read-only** — it cannot be imported, stored, read, or
organized, because the app's paper identity is arXiv-id-keyed (`import_to_library` gates on `ArxivId.parse()`).
This is the standing **generic-import carve-out** (HUMAN.md §3).

**What P-Sources is.** It makes a non-arXiv paper a **first-class library citizen** — importable, storable,
**readable (PDF day-one)**, and organizable (library / collections / tags / notes / on-device search). Per the
Co-Founder steer, the reader/detail/organize/import surfaces get genuine first-class multi-source treatment (a
source badge, per-origin affordances, coherent reading), not a bare-minimum hack — while guarding against
gold-plating that isn't real value.

**The reframe (the load-bearing finding).** This is **NOT a scary migration phase** — it is a *seam-generalization*
phase. `papers.id` is already an **opaque `TEXT` PK** (`PaperEntity.kt:17`); `ArxivId` is an erased `@JvmInline
value class` that never reaches SQLite (`ArxivId.kt:9-10`); `papers` already carries `doi` / `pdf_url` (free-form)
/ `s2_paper_id` / `source` columns (`PaperEntity.kt:28-32`). So a source discriminator is **additive columns, zero
rows re-keyed**. The scariest migration in the app is **designed out, not attempted** (§2). The real cost lives in
three arXiv-specific seams: **hydration** (`PaperRepository.paper()` falls through to the arXiv Atom API on a
cache-miss, `PaperRepository.kt:65`), the **reader/PDF entry** (`ArxivId`-keyed, arXiv-only HTML fetch), and the
**import gate** (`ArxivId.parse()` *is* the `importable` predicate).

### Committed cut vs demand-gated / deferred

| In the committed cut (PS.0–PS.2) | Deferred | Home |
|---|---|---|
| Source-identity abstraction + additive `MIGRATION_6_7` | — | PS.0 |
| chemRxiv import → PDF read → organize (first-class) | — | PS.1 |
| bioRxiv/medRxiv PDF import + read | — | PS.2 |
| bioRxiv/medRxiv **follows/feeds** (+ `follows.origin` migration) | Demand-gated | **PS.3** |
| Non-arXiv **HTML reader** (bioRxiv JATS→reader; bioRxiv exposes `jatsxml`) | Backlog | — |
| **Universal import-by-DOI** (Crossref metadata + Unpaywall OA-PDF, +2 hosts) | Backlog | — |
| S2 arbitrary-host in-app PDF ("allow-once" escape hatch) | HUMAN.md carve-out | — |
| Unified cross-source query grammar | Do-not-build (per-tool DTOs already ship) | — |
| Speculative per-source `PaperRef` subclasses + resolver ladder | Add per-source when wired | — |

---

## 2. Identity abstraction — opaque PK + additive columns (no re-key)

**DECISION: keep the opaque `TEXT` PK on `papers`, add additive discriminator columns, re-key ZERO rows.** The
structured-PK-rebuild alternative is rejected.

- **arXiv rows** keep their bare id (`2403.09999`) — byte-untouched.
- **New non-arXiv rows** get a **namespaced composite** id (`chemrxiv:10.26434/…`, `biorxiv:10.1101/…`) as the
  opaque PK. Reserved prefixes: `{chemrxiv:, biorxiv:, medrxiv:, s2:}`; **anything un-prefixed ⇒ arXiv**.
  `PaperRef.fromStorageId` splits on the **first `:` only**, so a native id may itself contain `:`/`/` and
  round-trips losslessly. **The storage id is NOT URL-encoded** (ratified PS.0 deviation: the original "through
  `Uri.encode`" is un-implementable in the pure-JVM `:core:model` module — the id is a PK, and URL-safety is
  applied downstream where it enters a route/filename: `Routes.paperDetail` `Uri.encode`, `HtmlStorage` `/`→`_`).
- `ArxivId` is preserved (API-stable) and becomes the parse/URL body of one implementation of a narrow
  source-polymorphic **`PaperRef`** seam (`core/model`). PS.0 ships exactly two implementations: **`ArxivRef`**
  (the only one doing URL synthesis) and one generic **`ExternalRef(origin, nativeId, storedPdfUrl)`** that
  carries a stored URL and synthesizes nothing. **No `BioRxivRef`/`S2Ref` subclasses or resolver ladder** until a
  source is actually wired (speculative-generality cut).

### Why the rebuild is rejected (migration-risk)

A structured-PK rebuild re-keys **10+ CASCADE children** in lockstep (`library_entries`, `collection_papers`,
`paper_tags`, `notes`, `inbox_items`, `paper_authors`, `paper_categories`, `paper_embeddings`, `related_papers`,
`chunk_embeddings`) plus the FTS mirrors — a single missed table = orphaned/dropped user data — AND silently
strands the **two FK-less identity carriers**: `citation_edges.{citing_id, cited_id}` (composite PK, **no FK**,
`CitationEdgeEntity.kt:13-18`) and `chat_sessions.scope_id` (plain string, **no FK**, `ChatEntities.kt`), which
hold raw id text with no CASCADE. `ArxiverDatabase.build()` has **no `fallbackToDestructiveMigration`**, so any
drift **crashes on open**; destructive migration is forbidden (CLAUDE.md). The rebuild buys **zero** product
benefit over the additive path — `PaperRef` delivers identical type-safety and polymorphism without touching a
stored byte.

### The `source`/`origin` naming ruling (anti-repurpose, load-bearing)

`papers.source` **already exists and means provenance** — the `PaperSource` enum `{SEARCH, FOLLOW, SHARE_IN,
MANUAL, S2_STUB}` (`Paper.kt:6-12`), i.e. *how the row was cached*, orthogonal to *which repository it's from*. It
is **query-load-bearing**: `EmbeddingDao.kt:52` filters `WHERE p.embedded_at IS NULL AND p.source != 'S2_STUB'`.
**Overloading `source` for origin is FORBIDDEN** (it would reclassify provenance + corrupt embedding-eligibility —
the exact class of bug `MIGRATION_5_6`'s anti-repurpose ruling exists to prevent). P-Sources adds a **new `origin`
column** and leaves `source` untouched.

### The migration (PS.0) — `MIGRATION_6_7`, additive

```sql
ALTER TABLE `papers` ADD COLUMN `origin`    TEXT NOT NULL DEFAULT 'arxiv';
ALTER TABLE `papers` ADD COLUMN `native_id` TEXT;   -- nullable; DOI/native id for new sources (NULL for arXiv)
```
- The SQL literal `DEFAULT 'arxiv'` **must byte-match** the entity annotation `defaultValue = "'arxiv'"` (the
  inner single-quotes are part of the value — the identity-hash trap the `ChatEntities.kt` comments document).
- `origin` backfills every existing row with a constant (zero data movement — same shape as the shipped
  `MIGRATION_5_6`). `native_id` is NULL for arXiv (redundant with the bare-id PK).
- **`follows` is NOT touched in PS.0** — the `follows.origin` column + `(type,value)`→`(origin,type,value)` unique
  index rebuild rides PS.3 (its only consumer). Adding it now is a migration for an unused column.
- Ship in one commit: bump `ArxiverDatabase.VERSION = 7`, register `MIGRATION_6_7`, commit the KSP `7.json`, add
  `Migration6To7Test` (`runMigrationsAndValidate` identity-hash gate) with an explicit **"an existing arXiv row's
  `id` is byte-unchanged / zero rows re-keyed"** assertion.

### Byte-identity for an all-arXiv install

Rule: **"un-prefixed ⇒ arXiv; namespace only new non-arXiv rows"** — no `UPDATE papers SET id=…` backfill, ever.
So: every existing PK unchanged → all 10+ FK children + the two FK-less carriers point at the same strings (zero
re-key); `origin` defaults to `'arxiv'`; `ArxivRef("…").storageId == id.value` today → reader/PDF/nav routes
resolve identically. A pre-P-Sources install is behaviorally byte-identical.

---

## 3. Reading + ingestion for a non-arXiv paper

### Keystone safety fact

The security-critical half of the reader is **already source-agnostic**: `PdfDownloader.download(url, dest)` takes
a raw URL (not `ArxivId`-coupled) and streams any host to a file; `PdfRenderer`, `HtmlSanitizer`, the reader
WebView are source-neutral. And `AllowedHostsInterceptor` is registered as **both an application and a network
interceptor**, so an off-allowlist redirect hop **fails closed pre-socket** (tested; `assets.chemrxiv.org` is
explicitly asserted not-allowed). **An arbitrary-host OA-PDF URL simply fails closed** — no resolver needs trust.

### The reading ladder (PDF-first, never-strand)

| Tier | Surface | Availability |
|---|---|---|
| T0 Abstract | plain text over `paper.abstract` | Always (source-agnostic) |
| T1 In-app PDF | sandboxed `PdfRenderer` over a downloaded file | Any source with a same-host / allowlisted PDF URL |
| T2 Native HTML reader | LaTeXML transform | **arXiv only** (the `.ltx_*`/`bib.bib\d+`/arXiv-image transform does NOT generalize) |
| T3 External "open in browser" | existing external-link confirm | Arbitrary-host OA PDF that fails the allowlist |

`PaperEntity.pdf_url` is a free-form `NOT NULL` field that `Paper.pdfUrl` only *defaults* to the arXiv URL —
**PDF-day-one needs no new column**; the row stores the source's real PDF URL. T2 stays arXiv-only; PDF + abstract
is the coherent floor for every other source.

### OA-PDF resolution (cheapest+safest first)

| Order | Resolver | Gives | New host | Cut |
|---|---|---|---|---|
| R0 | Hit already carries it | chemRxiv `pdfUrl`; S2 `openAccessPdf.url` **stored verbatim, host-gated** | `www.biorxiv.org`, `www.medrxiv.org` (PS.2) | **PS.1 (chemRxiv) + PS.2 (bio/med)** |
| R1 | Deterministic `<doi>v<n>.full.pdf` synthesis | a version-resolved bioRxiv/medRxiv PDF URL | (reuses R0 hosts) | **Backlog** |
| R2 | Unpaywall `?email=` → `best_oa_location.url_for_pdf` | OA PDF for any DOI (arbitrary host) | `api.unpaywall.org` | Backlog |
| R3 | Crossref `?mailto=` → metadata (no hosted PDF) | metadata fallback | `api.crossref.org` | Backlog |

**PS.2 ships R0, not R1** (ruling, 2026-07-05): S2 hands back the paper's `openAccessPdf.url` directly, so
storing it verbatim is strictly smaller/safer than synthesizing a `<doi>vN.full.pdf` URL — synthesis needs a
version (`vN`) the S2 search site doesn't carry, and live `/early/` vs `doi.org` OA URLs don't fit one template.
R1 moves to Backlog. Source is classified by S2 **`venue`** (`bioRxiv`/`medRxiv`), never the DOI prefix — the
`10.1101/` prefix is shared across both (`core/model/S2Origin.kt`).

S2/Unpaywall PDF URLs are **arbitrary publisher hosts** — never blanket-allowlisted. Rule: **host already
allowlisted ⇒ import + render in-app; else read-only (abstract + external-open).** No "allow-once" escape hatch
(HUMAN.md carve-out). **Consequence — partial in-app coverage:** a bio/medRxiv hit is importable-for-in-app-read
only when S2's OA URL host is `www.biorxiv.org`/`www.medrxiv.org`; a `doi.org` or publisher-host OA URL stays
read-only. This is a scope-honesty fact, not a gap to paper over.

### Reader-coherence seams (hard PS.1 acceptance criteria — a non-arXiv paper must not dead-end)

1. **Source-dispatched hydration** — `PaperRepository.paper()` must NOT fall through to the arXiv Atom API for a
   non-arXiv `PaperRef` (else a chemRxiv cache-miss fires a garbage `arxiv.org` fetch). `PaperRepository.kt:65`.
2. **Per-origin gating of arXiv-only affordances** — hide "Open HTML" for non-arXiv origins (today unconditional,
   `PaperDetailScreen.kt:424` → would build `arxiv.org/html/chemrxiv:…`); make the share intent source-aware
   (today `paper.id.absUrl()` hardcodes `arxiv.org/abs/…`, `:216`). Non-arXiv detail screens show a **source
   badge** + the source's canonical URL.
3. **Filename/route safety** — the PDF-cache filename sanitizer strips only `/` (`PdfViewerViewModel.kt:66`); a
   `chemrxiv:…` id needs `[/:]`→`_`. Route params read as an opaque `String`/`PaperRef`, not `ArxivId(...)` (`:42`,
   and `HtmlReaderViewModel.kt:87`).

---

## 4. Rate-limit + AllowedHosts contract

**Per-host mutex — the approved reversal (Co-Founder nod, 2026-07-05).** arXiv keeps its ≥3s `ArxivRateLimiter`
singleton; **non-arXiv hosts get their own ~1.2s polite mutex** (matching the shipped S2/chemRxiv search-side
pattern). This reverses HUMAN.md §1 (2026-06-27 "no host-keying"), whose rationale held only while every host was
arXiv. **Guardrails:** `PdfDownloader.download` takes the limiter/mutex + `User-Agent` as parameters; a registry
returns arXiv's ≥3s **singleton** for every arXiv-group host; a **structural test asserts arXiv-host ⇒ the ≥3s
singleton** (no per-call bypass). `PdfDownloader` currently hardcodes the arXiv UA — thread a per-host UA.

**Egress footprint grows in approved, demand-gated steps:** chemRxiv (0 new hosts — already allowlisted, PS.1) →
bioRxiv/medRxiv (+`www.biorxiv.org`, `www.medrxiv.org` PDF hosts, PS.2; +`api.biorxiv.org` feeds, PS.3). Every new
host is a user-approved `AllowedHosts.ALLOWED` addition. Arbitrary-host PDFs are **never opened in-app** (fail
closed via the per-hop interceptor). arXiv's ≥3s red line is untouched (structurally enforced).

---

## 5. Backup wall

The `ExportedPaper` DTO (defined in **`LibraryExporter.kt`**, shared by BOTH `LibraryExport` and `ArxiverBackup`)
made `BackupManager.toEntity()` mint `id = arxivId`, `pdfUrl = ArxivId(arxivId).pdfUrl(version)` (a plausible-looking
404-on-fetch `arxiv.org/pdf/chemrxiv:…` URL — an active **URL-mangle**, not a crash) and never set `origin`
(`BackupManager.kt` toEntity + the join sites). **The moment a non-arXiv paper is storable this silently corrupts
data.** **Fixed in PS.1 (not deferred):** the DTO renames `arxivId`→a neutral `paperId` (keeping the legacy
`"arxivId"` JSON key via kotlinx `@JsonNames`, so a pre-P-Sources file still deserializes), adds `origin` (default
`"arxiv"`) + a nullable `pdfUrl`, and **drops `absUrl` as authority**. `toEntity()` derives origin/nativeId from the
PK via `PaperRef.fromStorageId` and carries the real `pdfUrl` verbatim — re-synthesizing the arXiv URL **only** for a
legacy row whose `pdfUrl` is null and origin is arXiv. `SCHEMA` bumps `"arxiver-backup/v1"` → `"arxiver-backup/v2"`,
and `import()`'s strict `require(schema == SCHEMA)` widens to `schema in {v1, v2}` (a bare bump would make every v1
file un-importable). The six-field allowlist DTO + forbidden-name walk stay green: the DTO gains only **non-sensitive
metadata** (`origin`/`pdfUrl`/`paperId`) — **never PDF bytes or HTML**. A chemRxiv round-trip test + a hand-written v1
back-compat test ship with it.

---

## 6. Subphase plan

- **PS.0 — Source-identity abstraction + additive schema (dark, no UI).** `PaperRef` seam (`ArxivRef` +
  `ExternalRef`) in `core/model`; `PaperEntity` +`origin`/`native_id`; `MIGRATION_6_7` (additive) + `7.json` +
  `Migration6To7Test` (byte-gate + zero-re-key assertion); `PaperMappers` map `ref`↔`storageId`/`origin`/
  `native_id`; `Paper.id: ArxivId → ref: PaperRef` behind a temporary shim deleted incrementally to keep every
  commit green. **DoD:** build green; nothing dispatches to a non-arXiv branch yet.
- **PS.1 — chemRxiv first-class (the MVP proof).** A chemRxiv chat hit → importable (source-aware `importable`
  predicate + `PaperRef`) → stored (`origin=chemrxiv`, `native_id`/`doi`, real `pdf_url`) → **PDF read** + abstract
  → organized (library/collections/tags/notes/on-device search ride the opaque `paper_id`, origin-blind). Folds
  in the §3 reader-coherence trio, the §4 rate-limiter parameterization, the §5 backup fix, and a **tested de-dup
  invariant** (an arXiv-resolvable paper — native id or via S2 `externalIds.ArXiv` — is stored under the **bare
  arXiv id**, never a `chemrxiv:`/`s2:` alias, else it forks library/notes/embeddings off the real row).
- **PS.2 — DOI-PDF sources.** bioRxiv/medRxiv PDF import+read via S2's **verbatim OA URL (R0)**, host-gated on
  `www.biorxiv.org`/`www.medrxiv.org` (+2 hosts); venue-classified (`S2Origin.kt`); de-dup wired at the S2 search
  site; a `doi.org`/publisher-host OA URL stays read-only → external-open (partial in-app coverage). Rides PS.0's
  seam. Also folds in the PS.1-deferred empty-dispatch guard. **Shipped 2026-07-05.**
- **PS.3 — bioRxiv/medRxiv follows + feeds (DEMAND-GATED).** `FollowSyncWorker` per-source branch (arXiv→Atom;
  bio→date/category feed via `api.biorxiv.org`); `follows.origin` column + unique-index rebuild (additive, explicit
  SQL + `8.json`); a bioRxiv taxonomy (`paper_categories` has no FK to `categories`, so foreign codes fit).
  bioRxiv has **no keyword search** (live-confirmed) — follows are category/date only; keyword discovery routes
  through the shipped S2 bridge. Built only if PS.1/PS.2 device usage proves demand.
- **CHECKPOINT P-Sources** (own commit `checkpoint: P-Sources`): build green; migration integrity (`Migration*Test`
  green, `7.json`/`8.json` byte-match, zero-re-key assertion, no destructive migration); red-line audit (every
  non-arXiv fetch host-gated + self-spacing; arXiv ≥3s singleton never serializes non-arXiv; backup redaction green;
  `:core:* ∌ :app`; no telemetry); end-to-end proof per shipped source (import→PDF→organize→on-device-search);
  device checks in `VERIFICATION.md` §Q-PS; HUMAN.md §3 marked resolved-for-schema (universal-DOI capstone deferred).

---

## 7. Red lines (held under multi-source)

- **arXiv ≥3s limiter, no bypass** — held via the §4 reversal (arXiv keeps the ≥3s singleton; structural test
  enforces arXiv-host ⇒ singleton). Non-arXiv hosts self-space via their own ~1.2s mutex.
- **AllowedHosts egress, fail-closed on redirects** — held by construction (app+network interceptor). Arbitrary-host
  OA PDFs fail closed → external-open, never opened in-app. Every new host is user-approved.
- **Backup wall** — DTO gains only non-sensitive metadata; never PDF bytes / HTML / tokens. Redaction test green
  (§5).
- **Token vault** — untouched; no resolver uses a key. **No telemetry** — deferred Crossref/Unpaywall `mailto`/
  `email` etiquette params identify *the app*, not the user (same as arXiv's UA); no analytics dependency.
- **`:core:* ∌ :app`** — `PaperRef`/`Source` live in `core/model`; the source-dispatch logic stays layer-clean.

---

## 8. Testing strategy

- **Off-device golden spine:** `Migration6To7Test` (identity-hash + zero-re-key, Robolectric in `test` so CI runs
  it); `PaperRef` round-trip (arXiv unchanged; namespaced composite via the opaque-PK first-colon scheme, not
  `Uri.encode`; `fromStorageId` prefix
  dispatch); the **de-dup invariant** (arXiv id always wins the storage id); source-dispatched hydration (no
  arXiv-Atom fetch for a non-arXiv ref); the **backup round-trip** with a chemRxiv/bioRxiv fixture; the
  **rate-limiter structural test** (arXiv-host ⇒ ≥3s singleton); disclosure/reader per-origin gating.
- **Device (`VERIFICATION.md` §Q-PS, never blocks `[x]`):** real chemRxiv/bioRxiv PDF fetch + in-app read; the
  `assets.chemrxiv.org` CDN host question (Q5, resolved on a device capture); import→organize→on-device-search of a
  non-arXiv paper end-to-end; per-host polite spacing under a concurrent arXiv turn.

---

## 9. Decision log & open items

- **Identity: opaque PK + additive columns, no re-key** — the rebuild is rejected (§2). Validated at file:line.
- **Rate-limiter host-keying reversal** — approved by the Co-Founder 2026-07-05 (HUMAN.md §1).
- **Scope: PS.0+PS.1+PS.2 committed, "rich and sophisticated"** — approved 2026-07-05; PS.3 demand-gated; JATS
  reader / universal-DOI / S2 allow-once → backlog / HUMAN.md carve-out.
- **Open device item (Q5):** chemRxiv PDFs may serve off-host from `assets.chemrxiv.org` (not allowlisted); default
  to top-level `pdfUrl` + external-open, allowlist a fixed CDN sub-domain only if a device capture proves it needed.
- **Sources referenced (research):** bioRxiv/medRxiv `/details` API (`pdf_url`, `jatsxml`, no keyword search),
  Cambridge Open Engage (chemRxiv, searchable), Semantic Scholar (`externalIds`/`openAccessPdf`, the cross-source
  bridge), Crossref/Unpaywall (universal-DOI, deferred).

See the ROADMAP **Phase P-Sources** section for the PS.0–PS.3 rows + **CHECKPOINT P-Sources** criteria. Each
subphase is one PR, gated by a planning pass → personal adversarial validation → post-diff adversarial review →
green `./gradlew build` → CI → self-merge (subphases self-approve within this approved phase).
