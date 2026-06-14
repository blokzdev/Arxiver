# Arxiver — Roadmap & Progress Tracker

> **This file is the single source of truth for progress.** Work proceeds top-to-bottom. Every task gets checked off in the same commit that completes it. Every phase ends with a **CHECKPOINT** (definition of done) that must pass before the next phase begins. See CLAUDE.md for the workflow protocol.

Legend: `[ ]` todo · `[x]` done · `[~]` in progress · `[!]` blocked (reason noted inline)

---

## Phase D — Documentation (docs-first)

- [x] D1. PRD (`docs/PRD.md`)
- [x] D2. Architecture (`docs/ARCHITECTURE.md`)
- [x] D3. Data spec (`docs/SPEC-DATA.md`)
- [x] D4. Search spec (`docs/SPEC-SEARCH.md`)
- [x] D5. Claude bridge spec (`docs/SPEC-CLAUDE-BRIDGE.md`)
- [x] D6. UI spec (`docs/SPEC-UI.md`)
- [x] D7. README, ROADMAP, CLAUDE.md
- [x] **CHECKPOINT D:** docs committed & pushed; user has visibility into full plan

## Phase 0 — Foundation

- [x] 0.1 Gradle scaffold: settings, version catalog (`gradle/libs.versions.toml`), root build files, wrapper; Kotlin 2.x, AGP 8.x, JDK 17 toolchain, compileSdk 35 / minSdk 26
- [x] 0.2 Modules created per ARCHITECTURE §2 (`:app`, `:core:{model,common,database,network,ml,search,claude}`) with placeholder sources compiling
- [x] 0.3 Hilt wired; single MainActivity with Compose + Navigation skeleton (4 bottom-nav stubs)
- [x] 0.4 Theme: M3 dynamic color + brand fallback, dark theme, typography per SPEC-UI §1
- [x] 0.5 CI: GitHub Actions workflow — build + unit tests + lint on push/PR, debug APK artifact uploaded
- [x] 0.6 Static analysis: Android lint configured as errors-fail; ktlint or kotlinter wired into `check`
- [x] **CHECKPOINT 0:** `./gradlew build` green locally and in CI (run #3 green; APK artifact uploaded). Manual install smoke deferred to user — no emulator in cloud env

## Phase 1 — Browse & Read

- [x] 1.1 `:core:model` paper domain types; `:core:database` Room setup with `papers`, `authors`, `paper_authors`, `categories`, `paper_categories` (SPEC-DATA) + migration test harness
- [x] 1.2 Category taxonomy asset (full arXiv taxonomy) + build-time seed
- [x] 1.3 `:core:network` ArxivApiClient: Atom parser (XmlPullParser), query builder, **global 3s rate limiter**, paging; unit tests with recorded Atom fixtures
- [x] 1.4 Browse feature: category groups → category → latest listing (paged), follow toggle (persists, sync comes in Phase 2)
- [x] 1.5 arXiv online search: field-prefix query support, filters, queue-state UI
- [x] 1.6 Paper detail screen per SPEC-UI (sans Phase-2+ sections); viewed papers upserted to DB
- [x] 1.7 PDF: download manager + in-app reader (night invert, paging)
- [x] 1.8 Deep links / share-in for arxiv.org URLs
- [x] **CHECKPOINT 1:** CI green (cumulative, run #6+); manual install smoke deferred to user (no emulator in cloud env)

## Phase 2 — Library & Index

- [x] 2.1 Schema: `library_entries`, `collections(+papers)`, `tags(+papers)`, `notes`, `follows`, `inbox_items` + migrations
- [x] 2.2 Library feature: save/status/rating, collections, tags, notes (markdown); multi-select bulk actions deferred to 4.4 where they ship with the dispatch sheet
- [x] 2.3 FTS5: `papers_fts` + triggers + bm25-weighted local search tab (keyword-only at this phase)
- [x] 2.4 SyncEngine: `FollowSyncWorker` (cursor-based, per SPEC-DATA follows), Inbox feed with swipe triage
- [x] 2.5 Today screen: recency-sorted inbox, sync status, empty states
- [x] 2.6 Export: JSON + BibTeX of library
- [x] **CHECKPOINT 2:** CI green (run #6); DAO/FTS behavior covered by Robolectric tests; manual smoke deferred to user

## Phase 3 — Semantic Engine

- [x] 3.1 `:core:ml`: model download manager (pinned URL + checksum, progress UI), WordPiece tokenizer (Kotlin, unit-tested against reference tokenizations), ONNX Runtime session, embed function per SPEC-SEARCH §6
- [x] 3.2 `VectorStore`: BLOB store + chunked cosine top-K adopted for v1 (pre-approved fallback; sqlite-vec moved to v2 backlog — no Android-ready artifact, scan is fast at orbit scale)
- [x] 3.3 `EmbeddingWorker`: batch embedding of un-embedded papers; `embedding_meta` model guard
- [x] 3.4 Hybrid search fusion per SPEC-SEARCH §3 (weights 25/75, gate 0.7) + provenance badges; fusion math unit-tested — golden relevance set needs on-device inference, moved to Phase 5 manual verification
- [x] 3.5 Related papers precompute + detail-screen section
- [x] 3.6 Citation graph: SemanticScholarClient, `CitationSyncWorker` (nightly batch, stub rows), Connections list view
- [x] 3.7 Semantic triage: library k-means centroids, inbox scoring + "Likely relevant" section
- [x] **CHECKPOINT 3:** CI green (run #7); fusion/tokenizer/downloader unit-tested; on-device semantic quality check deferred to user smoke (model inference needs a device)

## Phase 4 — Claude Bridge

- [x] 4.1 `:core:claude`: TokenVault (EncryptedSharedPreferences), routine config CRUD + management UI
- [x] 4.2 PayloadBuilder per SPEC-CLAUDE-BRIDGE §4 + golden-file tests; size guard; notes-redaction structural test
- [x] 4.3 RoutineTriggerClient + retry/queue (`DispatchWorker`); MockWebServer tests (200/401/5xx/offline)
- [x] 4.4 Dispatch sheet UI (routine → action → instruction → notes toggle → payload preview → send) wired into paper detail + library multi-select
- [x] 4.5 Action catalog incl. `weekly_review` auto-selection and `literature_scan`
- [x] 4.6 Dispatch history screen + retry
- [x] 4.7 "Copy routine starter instructions" generator
- [x] 4.8 Real-trigger E2E: first live ping returned HTTP 400, revealing the real fire-API contract (anthropic-version + anthropic-beta headers, /fire path, {"text": …} wrapper); RoutineTriggerClient + DispatchEnvelope adapted across three field iterations — user confirmed live ping and research dispatches succeed (2026-06-12)
- [x] 4.9 `relations` payload block (research-driven, SpatialClaw arXiv 2606.13673): ship on-device analysis primitives — pairwise embedding cosine + citation edges within the selection, top-3 corpus neighbors per paper — as an additive, optional payload section so routines compose relationships instead of re-deriving them; neighbors gated behind the notes privacy toggle (structural redaction tested)
- [x] **CHECKPOINT 4:** real-routine dispatch produced successful runs (user-verified 2026-06-12); contract pinned by MockWebServer tests; tokens absent from payloads/backups by structural tests; CI green

## Phase 5 — Polish & Release

- [x] 5.1 Onboarding flow (welcome → starter category picker → first sync; model + routine steps folded into Settings/notes per scope)
- [x] 5.2 Settings: sync cadence, model mgmt (download/re-index/delete), PDF storage mgmt, Claude links, about (theme follows system — dedicated toggle deferred)
- [x] 5.3 Backup/restore: single-JSON export/import (library+notes+tags+collections+follows+routine URLs, token-free by construction — asserted in tests), idempotent import, embedding backfill scheduled; SAF picker in Settings. (Zip wrapper deferred — single JSON suffices at v1 scale)
- [!] 5.4 [needs-user/device] Performance pass: cold start < 2s, search < 300ms @ 5K corpus — measurement requires a physical device; baseline profile generation likewise
- [!] 5.5 [needs-user/device] Accessibility: TalkBack labels shipped on all actionables during feature work; the verification pass (TalkBack walkthrough, font-scale 1.3x) needs a device
- [x] 5.6 Release engineering: signing-from-env config + tag-triggered release workflow shipped; repo secrets (KEYSTORE_*) configured and v1.0.0 tag released — signed APK published 2026-06-11 (see memory: release-engineering-state)
- [x] 5.7 README: install guide + Claude connection guide (screenshots pending first device run)
- [!] 5.8 [needs-user/device] Install-from-scratch test of the published v1.0.0 APK on a physical device
- [ ] **CHECKPOINT 5 = v1.0.0:** GitHub Release published ✅ (2026-06-11, signed `arxiver-v1.0.0.apk`); remaining: PRD §7 success criteria verified and recorded — device-bound, blocked with 5.4/5.5/5.8

## Phase 6 — Routine catalog & guided setup

> Goal: a curated catalog of Claude routine templates plus an in-app guided setup flow, so a non-expert goes from template → verified working routine without guesswork. The catalog design is reviewed by the user **before** implementation. 4.8/CHECKPOINT 4 (real-trigger E2E) gates only the verification tasks (6.6), not the design ones.

- [x] 6.1 Routine template catalog proposal: 8 paper-centric templates (name, purpose, instruction preamble + shared recognition core, API-trigger config, minimal connector set, action mapping) — drafted as `docs/SPEC-ROUTINES-CATALOG.md`; user reviewed & approved via the Phase 6 plan, 2026-06-12, no edits requested
- [x] 6.2 Spec finalization: SPEC-ROUTINES-CATALOG approved as drafted; SPEC-CLAUDE-BRIDGE extended with §8 guided-setup + verification contract (opt-in test-dispatch semantics, save-before-verify, error taxonomy table)
- [x] 6.3 Catalog in app: `RoutineTemplateCatalog` (versioned Kotlin object in `:core:claude`, invariant-tested) + browse/detail screens with per-template "copy instructions" via shared recognition core (`RoutineStarterInstructions.generateFor`); entry points: routines empty state + Settings → Claude
- [x] 6.4 Guided setup wizard: 3-step `RoutineSetupScreen` (create on claude.ai → connect URL+token with live validation → opt-in verify), save-before-verify per SPEC §8.1, template-prefilled via optional nav arg; `RoutineSetupGateway` seam keeps the ViewModel unit-tested; tokens flow only through the existing TokenVault path
- [x] 6.5 Verification + troubleshooting: opt-in test ping in the wizard (consented — fire API has no dry-run, so "auto" became opt-in per SPEC §8.1), typed `VerificationError` taxonomy (bad token / wrong URL 404 / bad request 400 / rejected / 5xx-queued / offline-queued / vault miss) with cause+fix cards and Retry / Edit / Keep-anyway actions; mapper + ViewModel covered per error class
- [!] 6.6 [needs-user] Real-trigger validation: set up ≥2 catalog templates via the wizard against live routines and confirm end-to-end runs (contract itself already live-verified in 4.8); record findings in SPEC-CLAUDE-BRIDGE — blocked: needs the user's Claude routines + device
- [ ] **CHECKPOINT 6:** catalog + wizard + error taxonomy shipped and test-covered; tokens absent from logs/exports (structural tests); CI green — remaining: the live-validation leg rides 6.6 (user-bound, mirrors CHECKPOINT 5 handling)

---

## Phase UX — Design & UX elevation sweep (side-phase, pre-v1.1.0)

> User-directed (2026-06-12): elevate design/UI/UX across all surfaces to a world-class modern sleek experience. One PR, dependency-ordered commits, every commit green. Honors the existing SPEC-UI promises that were never built (skeletons §4, swipe a11y alternatives §5, score bar §3, expandable abstract §3).

- [x] UX.1 Foundation: complete fallback palette (teal tertiary = machine-signal accent, surface-container tiers), custom shapes, motion + spacing tokens; SPEC-UI §1 color-semantics + motion/spacing spec
- [x] UX.2 Shared components: SectionHeader, EmptyState, skeleton set, StatusChip (ScoreBar lands with the cell); richer preview fixtures
- [x] UX.3 App shell: splash screen, nav transitions (fade-through tabs, slide pushes), predictive back
- [x] UX.4 PaperListItem v2: badge/score/status/rating slots, animated selection, all call sites
- [x] UX.5 Screen passes: Today (pull-to-refresh, undo triage, haptics, a11y actions) · Browse (animated groups) · CategoryFeed (skeletons, refresh, scroll-to-top) · Search (pill field, segmented tabs, skeletons) · Library (selection UX, undo delete, tag cloud) · PaperDetail (scroll-aware bar, expandable abstract, animated save/rating) · Connections+PDF (chips, page pill) · Settings (ListItem anatomy) · Onboarding (brand moment) · Claude surfaces (status tones, sheet polish, hardcoded-string fix)
- [x] UX.6 DoD sweep: light/dark preview pairs across all screens (DispatchSheet excepted — modal sheets don't render in previews; PDF is renderer-bound); decorative icons null-described, actionables labeled; detail hero wraps rather than ellipsizing
- [x] UX.7 Release prep: versionName 1.1.0 / versionCode 2 (fixes stale 0.1.0 identity that v1.0.0 shipped with)
- [x] UX.8 v1.1.1 release-stability hotfix: R8 full mode stripped Hilt multibinding modules in release builds — TodayViewModel's `HiltModules$BindsModule` (crash on first navigation to Today, i.e. onboarding "Start reading") and every `@HiltWorker` assisted factory (background sync uninstantiable since v1.0.0; no release build had ever been device-tested). Fix: `android.enableR8.fullMode=false` + explicit keep rules, verified in the R8 mapping. Plus: local-only crash reporter (trace saved on device, copyable next launch), onboarding completion race fix (navigate after the onboarded write, not before), headless first-run diagnostic test
- [x] UX.9 v1.1.2 release-stability hotfix: second R8 obfuscation casualty caught by the v1.1.1 crash reporter — Preferences DataStore's bundled protobuf-lite (`PreferencesProto`) resolves its generated fields by name via reflection, and compat-mode renaming still broke that (`Field value_ for q1.e not found`), crashing the onboarding `setOnboarded()` write. Fix: **disable release minification** (`isMinifyEnabled`/`isShrinkResources` = false) — kills the whole R8-obfuscation bug class for a sideloaded build rather than chasing casualty #3 (Room/Retrofit/ONNX, none device-tested shrunk). DataStore/protobuf keep rules landed pre-emptively; versionCode 4 / versionName 1.1.2

## Phase M — Maintenance & Hardening (post-v1.1.x, ships as v1.2.0)

Sweep findings from a three-lens app-wide audit (deferred/stub inventory, conventions/red-lines, UX/test coverage). Red lines audited clean (tokens, rate limiter, hosts, layering, `AppResult`, strings). v1.1.2 stays untagged — this work bundles with it into a single **v1.2.0** release. Device-bound blocked items (5.4/5.5/5.8/6.6, SPEC-SEARCH golden set) are out of scope here.

- [x] M.1 Build & convention hygiene: replace the 4 hardcoded `Dispatchers.IO` sites (SettingsViewModel ×3, PdfViewerScreen renderer) with the injected `DispatcherProvider`; add `lint { ignoreTestSources = true }` to end the intermittent Kotlin-FIR lint analyzer crash on `OnboardingFlowTest.kt`
- [x] M.2 Error/empty/loading consistency: promote `ErrorState` to `ui/components/` (shared); add loading+error states to Onboarding and a proper error state to PaperDetail's notFound; swap ad-hoc empty states (FilteredPapersScreen, RoutinesScreen) to shared `EmptyState` + loading skeleton
- [x] M.3 Compose previews + a11y: added light/dark `@Preview` pairs (onboarding loading/error, FilteredPapers empty/list, SearchScreen local results empty/populated, NewCollectionDialog). A11y verified already-correct — `PaperListItem` exposes selection via row-level `semantics { selected }` (icon `null` is right) and `StatusChip` always carries a text label (non-color cue present); no change needed (audit over-reported). RoutinesScreen empty/list covered by EmptyState + existing RoutineRow previews
- [x] M.4 Core (non-UI) test coverage: `:core:common` (AppResult/AppError), `:core:database` schema/reopen harness + LibraryDao (entries/collections/tags/notes), `:core:model` (Paper defaults, taxonomy), `:core:network` (pagination bounds, Offline mapping, 429). **Found & fixed a latent crash**: `observeCollectionPapers`/`observeTagPapers` LEFT-JOIN `library_entries` but `LibraryRow.status` is non-null — a collection/tag paper without a library entry returned NULL status → NPE; queries now `COALESCE` status/added_at. Added `ArxiverDatabase.VERSION` as the single source of truth for the schema version
- [x] M.5 ViewModel test coverage + onboarding logic in CI: direct tests for 9 ViewModels (Library, Browse, FilteredPapers, Today, PaperDetail, CategoryFeed, Onboarding, PdfViewer, Connections) via the real-repo + in-memory-DB pattern, `WorkManagerTestInitHelper` for sync-dependent VMs, and MockWebServer for network-backed repos (added `work-testing` + `mockwebserver` test deps). Onboarding: rather than force the flaky Compose `OnboardingFlowTest` into CI (it's order-flaky via the process-wide DataStore-by-name singleton), the first-run *logic* it proved (finish() persists onboarded + enqueues sync before navigating) is now covered headlessly by `OnboardingViewModelTest`; the Compose test stays a documented manual diagnostic. **Deferred** (heavy dep graphs — DispatchRepository's 8 deps, the ML/embedding stack; their core logic is already covered in `:core:claude`/`:core:search` + RoutineSetupViewModelTest/VerificationErrorTest/PayloadBuilderTest): SearchViewModel, SettingsViewModel, DispatchViewModel, DispatchHistoryViewModel → tracked below
- [x] M.6 Docs/process + v1.2.0 release prep: documented the payload schema-version bump process (SPEC-CLAUDE-BRIDGE §4.1: bump `ArxiverPayload.SCHEMA` + regenerate goldens + edit spec, guarded by `PayloadBuilderTest`); set `versionName = "1.2.0"` (reuses the unreleased versionCode 4); Decision log + release-engineering memory updated
- [x] **CHECKPOINT M:** clean `./gradlew build` green (lint flake gone), all new core + ViewModel suites pass, first-run logic covered headlessly (`OnboardingViewModelTest`; Compose `OnboardingFlowTest` stays a documented manual diagnostic), `:app:assembleRelease` APK verified unobfuscated (real `dev.blokz.arxiver.*` / `PreferencesProto` / `value_`, no mapping dir), versionName 1.2.0

## Phase F1 — v1.2.1 field fixes (post-v1.2.0 device run, ships v1.2.1)

First on-device session (2026-06-14) confirmed v1.2.0 works end-to-end and surfaced three issues (tracked in `VERIFICATION.md` §I).

- [x] F1.1 Sync spinner never stops: `observeSyncRunning()` flagged `RUNNING || ENQUEUED`, and `FollowSyncWorker` retried forever on any per-follow failure → perpetually ENQUEUED. Fix: indicator reflects `RUNNING` only; worker caps retries (`runAttemptCount`) and reports success at the cap (periodic sync retries later). Worker test added.
- [x] F1.2 Phantom empty row: trailing `HorizontalDivider` on the last `PaperListItem` across Today/Library/Search/CategoryFeed lists → pass `showDivider = index != lastIndex`. PaperDetail trailing-strip fix deferred to device confirmation (`VERIFICATION.md` I3).
- [x] F1.3 Add-to-collection: data layer existed; wired `CollectionsSection` in PaperDetail (FilterChip toggle + "new collection") mirroring `TagsSection`, new `observeCollectionMemberships` DAO query, VM add/remove/create. DAO + ViewModel tests added. (Bulk add from Library multi-select → backlog.)
- [x] F1.4 `VERIFICATION.md` updated (v1.2.0 verified items + §I re-checks); versionName 1.2.1 / versionCode 5.
- [ ] **CHECKPOINT F1:** `./gradlew build` green; release APK unobfuscated; v1.2.1 cut and the §I items re-verified on device.

## Phase v2 — Richer experience (drafted 2026-06-14; not yet scheduled)

Research-informed (see Decision log). Themes, dependency-ordered within each:

- **Reader depth:** in-app PDF highlight/annotation anchored to text + margin notes (new DB migration); TTS read-aloud (`TextToSpeech`); full-text PDF indexing (extract → FTS + embeddings, feeds RAG below).
- **Smarter discovery:** per-user recommendation beyond centroids — on-device logistic-regression/linear-SVM over embeddings (à la arxiv-sanity) trained on saved/dismissed; "more like this" from any paper (reuse `VectorStore`); author following; trending within follows.
- **Visual & home:** visual citation-graph canvas (replaces list Connections); home-screen widgets via **Glance**; reading queue + streaks; relevance-ranked Today.
- **Claude-powered understanding** (research-corrected):
  - **Chat-with-paper / chat-with-knowledge-base** — **BYOK Anthropic Messages API** (key in `TokenVault`), *separate from the repo-scoped Routines bridge*. On-device **RAG**: reuse bge embeddings + `VectorStore` to retrieve from a user-curated **knowledge base** (flagged papers' abstract+notes, later full text); only retrieved chunks + question leave the device, behind an explicit confirm. Anthropic has no embeddings API, so retrieval stays on-device (privacy-preserving) — only generation calls Claude.
  - **On-device summaries** via the same BYOK path.
  - **Routine result round-trip — out of scope (research):** Claude Code routines are repo-scoped cloud sessions; the fire API returns only a session id/URL, not results (surfaced at claude.ai/code as diffs/PRs). An in-app inbox would need the routine to POST to a user-hosted webhook relay the app polls. Routines stay the outbound dispatch bridge as-is.

## v2 infra backlog (do not work on these)

**Re-enable R8 shrinking** behind audited keep rules + on-device smoke (disabled in v1.1.2) · **VM tests for heavy-dep ViewModels** (Search/Settings/Dispatch/DispatchHistory) · Play Store · tablet layouts · HTML paper rendering (ar5iv) · sqlite-vec.

## Decision log

| Date | Decision |
|---|---|
| 2026-06-14 | v1.2.0 verified working on device; v1.2.1 fixes the three issues found (sync spinner, empty row, add-to-collection). v2 roadmap drafted (Phase v2). **Research correction:** Claude Code routines are repo-scoped cloud sessions and the fire API returns only a session id/URL (not results) — so the long-planned "routine result round-trip / webhook inbox" is impractical without a user-hosted relay and is dropped. v2 chat-with-paper will instead be **BYOK Anthropic Messages API + on-device RAG over a user-curated knowledge base**, separate from the Routines bridge. Sources in the plan file / SPEC-CLAUDE-BRIDGE. |
| 2026-06-11 | Stack: Kotlin/Compose; on-device embeddings only; Claude scope = routines trigger only; sideload-first (user-approved) |
| 2026-06-11 | Single SQLite engine for relational/FTS/vector/graph; orbit-indexing not full mirror; features as packages in `:app` |
| 2026-06-11 | bge-small-en-v1.5 (384d) ONNX, downloaded not bundled; sqlite-vec with BLOB fallback behind `VectorStore` |
| 2026-06-11 | FTS4 + matchinfo-BM25 instead of FTS5 (unavailable in platform/Robolectric SQLite); BLOB vector store adopted, sqlite-vec → v2 backlog; CLS pooling per BGE convention (spec updated) |
| 2026-06-11 | v1.0.0 released via tag-triggered CI; keystore + credentials held offline by maintainer, CI signs from Actions secrets |
| 2026-06-11 | In-repo memory harness adopted (`.claude/memory/` + `MEMORY.md` index, protocol in CLAUDE.md) for continuity across local/cloud/mobile sessions; secrets and personal context excluded by rule |
| 2026-06-11 | Phase 6 added: user-reviewed routine template catalog + guided setup wizard with auto-verification (catalog review precedes implementation) |
| 2026-06-12 | Live fire-API contract adopted (4.8): POST …/routines/{id}/fire with anthropic-version + anthropic-beta headers, payload wrapped as {"text": json}; URL normalization appends /fire; starter instructions reworded |
| 2026-06-12 | DispatchEnvelope adopted after live runs: turns are self-describing (header + instruction + paper list + fenced arxiver/v1 JSON); pings carry a stand-down directive + confirm dialog (fire API has no dry-run) |
| 2026-06-12 | `relations` payload block added (4.9), non-breaking within arxiver/v1 — design transfer from SpatialClaw (arXiv 2606.13673, delivered via the self-improvement routine): expose composable on-device perception primitives (similarity/citations/neighbors) at the routine action interface; library-revealing neighbors ride the include_notes gate |
| 2026-06-12 | Routine catalog (Phase 6) = versioned Kotlin object in `:core:claude` (à la ArxivTaxonomy): no DB rows/migrations, no template↔routine link; instruction text canonical in code (content destined for claude.ai, exempt from strings.xml like taxonomy names), golden-tested against SPEC-ROUTINES-CATALOG |
| 2026-06-12 | Wizard verification is opt-in, never automatic (fire API has no dry-run): routine saved before any ping, stand-down ping is the verification, failures map to typed `VerificationError` taxonomy with per-class troubleshooting (SPEC-CLAUDE-BRIDGE §8) |
| 2026-06-12 | UX elevation sweep (Phase UX, user-directed): teal tertiary = machine-signal accent with documented color-role semantics, motion/spacing/shape tokens, shared SectionHeader/EmptyState/StatusChip/skeleton components, skeletons replace spinners per SPEC-UI §4, triage/collection deletes gain undo, swipe actions gain TalkBack equivalents per §5; versionName fixed to 1.1.0 (v1.0.0 APK had shipped self-identifying as 0.1.0) |
| 2026-06-12 | R8 compat mode adopted (`android.enableR8.fullMode=false`) + Hilt keep rules after v1.1.0 field crash: full mode strips Hilt multibinding modules (ViewModel map bindings, @HiltWorker factories) — diagnosed from the release mapping, no device trace needed. Local-only crash reporter adopted (sideload distribution has no crash pipeline; no-telemetry red line holds: trace stays on device) |
| 2026-06-14 | `VERIFICATION.md` added as the on-device verification ledger (CI can't run a device; signed-APK/worker/ML/perf/a11y behavior is only observable on hardware, and R8 bugs already slipped through twice). Device-bound checks moved out of ROADMAP `[!]` limbo into a batched checklist + dated log; folded into the CLAUDE.md loop (orient scans it; device-only verification never blocks `[x]` but is tracked there; user device-session reports update it in the same commit) |
| 2026-06-14 | v1.1.2 left untagged; its crash fix bundled with the Phase-M maintenance/hardening sweep into a single **v1.2.0** release (still in development, so no throwaway patch). `versionName 1.2.0` reuses the never-released `versionCode 4`. Phase M (user-directed app-wide sweep) outcome: red lines audited clean; dispatchers injected + lint test-source flake fixed (M.1); error/empty/loading state unified on shared components (M.2); preview pairs added, a11y verified already-sound (M.3); core + ViewModel test coverage expanded, surfacing & fixing a latent collection/tag NPE (M.4–M.5); heavy-dep ViewModels (Search/Settings/Dispatch) deferred to backlog with rationale |
| 2026-06-14 | Latent NPE fixed (found via M.4 tests): adding a paper to a collection/tag does not create a `library_entries` row, so `observeCollectionPapers`/`observeTagPapers` (LEFT JOIN) returned NULL `status` into the non-null `LibraryRow.status` → crash when viewing such a collection/tag. Fixed with `COALESCE(le.status,'to_read')` + `COALESCE(le.added_at,…)` in both queries (no schema change). LibraryDaoTest guards it |
| 2026-06-14 | Release minification disabled (`isMinifyEnabled`/`isShrinkResources` = false) after the second R8 obfuscation casualty (v1.1.2): Preferences DataStore's bundled protobuf-lite resolves generated fields by name via reflection, so even compat-mode renaming broke the onboarding write (`Field value_ ... not found`, caught by the v1.1.1 crash reporter). Since the signed APK can't be device-tested in CI, surgical keep rules just defer casualty #3 (Room/Retrofit/ONNX); for a single-user sideloaded app, shrinking buys ~nothing, so turning it off eliminates the whole bug class. Re-enabling shrinking (with audited keep rules + on-device smoke) deferred to the v2 backlog; protobuf keep rules landed now so that path is safe |
