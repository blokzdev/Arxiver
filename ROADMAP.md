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
- [!] 4.8 [needs-user] (blocked: awaiting a test routine trigger URL+token from the user — mock-verified meanwhile) End-to-end test against a real Claude routine trigger — **ask the user for a test routine URL+token when reaching this task**
- [ ] **CHECKPOINT 4:** mock-verified contract + real-routine dispatch produces a successful run; tokens demonstrably absent from logs/backups; CI green

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

- [ ] 6.1 [needs-user] Routine template catalog proposal: 6–10 paper-centric use cases, each with name, purpose, optimal instruction text, trigger type + config, minimal connector set (see memory: claude-routines-ui-contract), and payload/action mapping — drafted as `docs/SPEC-ROUTINES-CATALOG.md` and presented for user review
- [ ] 6.2 Spec finalization: fold review feedback into SPEC-ROUTINES-CATALOG; extend SPEC-CLAUDE-BRIDGE with the guided-setup + verification contract (test-dispatch semantics, error taxonomy)
- [ ] 6.3 Catalog in app: bundled versioned template data + browse/detail UI (what each template does, what it needs); per-template "copy instructions" built on the 4.7 generator
- [ ] 6.4 Guided setup wizard: stepper walking the user through creating the routine at claude.ai/code/routines (API trigger) → copying URL + token → pasting into Arxiver; input validation at entry; tokens only via TokenVault
- [ ] 6.5 Auto-verification: test dispatch on save with success confirmation; graceful failure handling + troubleshooting per error class (401 bad token, 404 wrong URL, 5xx, rate-limit, offline) with actionable fixes
- [ ] 6.6 (depends on 4.8) Real-trigger validation: run ≥2 catalog templates end-to-end against live routines; adapt `RoutineTriggerClient` if the contract differs from Bearer-POST; record findings in SPEC-CLAUDE-BRIDGE
- [ ] **CHECKPOINT 6:** user-reviewed catalog shipped in-app; wizard takes a fresh user from template to verified working routine; all error paths covered by tests; tokens absent from logs/exports; CI green

---

## v2 candidate backlog (do not work on these)

Routine **result round-trip** (webhook inbox) · in-app Claude API chat-with-paper · full-text PDF indexing · visual graph canvas · Play Store · tablet layouts · per-user preference learning beyond centroids (SVM à la arxiv-sanity) · HTML paper rendering (ar5iv)

## Decision log

| Date | Decision |
|---|---|
| 2026-06-11 | Stack: Kotlin/Compose; on-device embeddings only; Claude scope = routines trigger only; sideload-first (user-approved) |
| 2026-06-11 | Single SQLite engine for relational/FTS/vector/graph; orbit-indexing not full mirror; features as packages in `:app` |
| 2026-06-11 | bge-small-en-v1.5 (384d) ONNX, downloaded not bundled; sqlite-vec with BLOB fallback behind `VectorStore` |
| 2026-06-11 | FTS4 + matchinfo-BM25 instead of FTS5 (unavailable in platform/Robolectric SQLite); BLOB vector store adopted, sqlite-vec → v2 backlog; CLS pooling per BGE convention (spec updated) |
| 2026-06-11 | v1.0.0 released via tag-triggered CI; keystore + credentials held offline by maintainer, CI signs from Actions secrets |
| 2026-06-11 | In-repo memory harness adopted (`.claude/memory/` + `MEMORY.md` index, protocol in CLAUDE.md) for continuity across local/cloud/mobile sessions; secrets and personal context excluded by rule |
| 2026-06-11 | Phase 6 added: user-reviewed routine template catalog + guided setup wizard with auto-verification (catalog review precedes implementation) |
