# VERIFICATION.md — on-device verification ledger

Cloud CI builds and runs the JVM/Robolectric suite, but it **cannot run the app on a
device**. Several things are only observable on real hardware (signed-APK behavior,
background workers, on-device ML, performance, TalkBack), and Arxiver has already shipped
bugs that CI couldn't catch (R8 obfuscation crashed onboarding in v1.1.0 and v1.1.1). This
file is the single batched checklist of everything that needs a human + a device, plus a
dated log of what's actually been observed.

**Status of CI-side work lives in `ROADMAP.md`; status of device-side work lives here.**

## How to use this
- When you do a device run, work the checklist for the **current target build**, set each
  item's status + the build/date, and append a dated entry to the Verification log.
- A `[needs-user/device]` or `[needs-user]` task in ROADMAP is **mirrored here** as the
  concrete thing to check — ROADMAP says "blocked on device", this says "here's how".
- `[x]` in ROADMAP never depends on a device check (CI green is the gate); device checks
  ride here instead so they're never lost.

Legend: `[ ]` pending · `[x]` verified on device (build/date) · `[~]` partial ·
`[!]` failed (with pointer) · `[-]` superseded / n/a

**Current target build: v1.2.2 (versionCode 6) — staged.** v1.2.0 verified working
end-to-end on device (2026-06-14). v1.2.1 fixed the sync spinner (I1 ✅ confirmed) and
added add-to-collection; v1.2.2 fixes the bottom blank strip at its real root (nested-
Scaffold inset double-apply). §I tracks the device re-checks.

---

## A. Release smoke / first run
- [x] **A1 Install from scratch** — sideload the signed APK on a clean device. _Verified v1.2.0 (2026-06-14)._
- [x] **A2 Onboarding → Today** — follow ≥2 categories, tap "Start reading", land on **Today** with no crash. _Verified v1.2.0 (2026-06-14) — first build to clear the R8 crashes._
- [x] **A3 Onboarded flag persists** — reopen stays on Today, not onboarding. _Verified v1.2.0 (2026-06-14)._
- [x] **A4 Crash reporter** — an uncaught exception saves a trace; next launch shows a copyable dialog. _Verified v1.1.1 (2026-06-14): user copied the protobuf trace. From v1.2.0 traces are de-obfuscated (real names)._
- [ ] **A5 Zero crashes in normal flows** — exercise each tab without a crash. _(PRD §7.4)_ _Largely holds in v1.2.0 use; keep open until a full pass._

## B. Background sync & workers
> The `@HiltWorker` factories were R8-stripped v1.0.0–v1.1.1; v1.2.0 (minify off) is the
> first build where they run — confirmed by B1.
- [x] **B1 FollowSyncWorker** — followed categories populate the Today inbox after first sync. _Verified v1.2.0 (2026-06-14): fetching new articles works._
- [ ] **B2 EmbeddingWorker** — runs on unmetered network; un-embedded papers get embedded (Settings shows the embedded count climbing).
- [ ] **B3 CitationSyncWorker** — nightly citation fetch fills a saved paper's Connections (references/citations).
- [ ] **B4 DispatchQueueWorker** — a dispatch queued while offline sends once back online.
- [ ] **B5 Sync cadence** — periodic sync respects the interval set in Settings.

## C. On-device ML
- [ ] **C1 Model download** — pinned bge-small-en-v1.5 ONNX (~34 MB) downloads on unmetered with progress UI; checksum verified; survives delete/re-index in Settings.
- [ ] **C2 Embedding inference** — WordPiece tokenizer + ONNX session produce embeddings on device (unit-tested against reference tokenizations; on-device inference not yet run).
- [ ] **C3 Hybrid search relevance (golden set)** — ≥80% top-5 hit over the ~20-case fixture (SPEC-SEARCH §"Golden relevance set"); needs on-device inference, so it lives here not in CI.

## D. Performance _(ROADMAP 5.4 / PRD F5.4)_
- [ ] **D1 Cold start < 2s.**
- [ ] **D2 Hybrid search < 300ms** — PRD success criterion #2 says a 1,000-paper library; F5.4 says 5K. Measure both; note which corpus.
- [ ] **D3 List scrolling at 60fps** on the inbox/feed.
- [ ] **D4 Baseline profile** generated for release (startup optimization).

## E. Accessibility _(ROADMAP 5.5)_
- [ ] **E1 TalkBack walkthrough** — every screen's actionables are labeled and operable; swipe actions have custom-action equivalents (labels shipped during feature work; walkthrough not done).
- [ ] **E2 Font scale 1.3×** — layouts hold without truncation/overlap.
- [ ] **E3 Color-blind check** — StatusChip tones are distinguishable (each already carries a text label; confirm visually).

## F. Reader & content
- [x] **F1 PDF** — download + in-app reader, light and dark. _Verified v1.2.0 (2026-06-14)._
- [ ] **F2 Deep links / share-in** — opening or sharing an arxiv.org URL resolves to the paper detail.
- [x] **F3 Tagging** — add/remove tags on a paper; persists. _Verified v1.2.0 (2026-06-14)._
- [x] **F4 Search (functional)** — local + arXiv search return results. _Verified v1.2.0 (2026-06-14); relevance/latency targets still pending (C3/D2)._

## G. Claude dispatch (live — needs the user's routines) _(ROADMAP 6.6)_
- [ ] **G1 Guided setup wizard** — create on claude.ai → connect URL+token (live validation) → opt-in verify ping against a real routine. (Fire-API contract itself was live-verified in dev, task 4.8.)
- [ ] **G2 End-to-end** — set up ≥2 catalog templates via the wizard and confirm real runs; record findings in SPEC-CLAUDE-BRIDGE.
- [ ] **G3 Privacy preview** — the confirm sheet previews exactly what leaves the device; tokens never appear (redaction is structurally tested — confirm visually).

## I. v1.2.1 / v1.2.2 field fixes — re-verify on device
- [x] **I1 Sync spinner clears** — Today top-bar spinner stops (reverts to refresh icon). _Verified v1.2.1 (2026-06-14): screenshot shows the refresh icon, no perpetual spinner._
- [ ] **I2 No phantom empty row (lists)** — Today/Library/Search list ends cleanly. _v1.2.1 removed the trailing divider; the larger blank strip was the inset bug (I3), fixed in v1.2.2 — re-verify together._
- [ ] **I3 No bottom blank strip (app-wide)** — **root-caused v1.2.2:** nested Scaffolds double-applied the bottom nav inset → blank strip above the bottom bar on Today/list screens (and the gesture-bar clearance on detail screens). Fixed via `consumeWindowInsets` on the NavHost + trimmed PaperDetail's redundant trailing padding. Confirm the strip is gone on Today AND Paper Detail; note that a small inset above the gesture nav bar on detail screens is correct (content must clear the system bar).
- [ ] **I4 Add to collection** — from a saved paper's detail, add it to a collection; it appears under that collection (Library → Collections → open); toggling off removes it; survives relaunch; "New collection" from the picker works.

## J. AI providers (v2 / P1) _(SPEC-AI-PROVIDERS)_
> CI covers the transports (MockWebServer SSE), the registry/settings ViewModel
> (pure-JVM with fakes), and the key-vault contract (Robolectric, in :core:ai).
> What's left for hardware: the **hardware-backed Keystore** path and **real keys**.
> The **Settings → AI → AI providers** screen is built (P1.1b); these need a real key.
- [ ] **J1 Key vault on hardware Keystore** — a saved provider key survives app restart on a real device (Robolectric uses a software keystore; confirm the AndroidKeyStore-backed `EncryptedSharedPreferences` round-trips on device). _(P1.1a)_
- [ ] **J2 Claude test connection** — Settings → AI → AI providers, paste a real Anthropic key, Save, Test connection → "Connection OK"; a wrong key → "key rejected"; airplane mode → "offline". _(P1.1b UI built; needs a user-provided key)_
- [ ] **J3 Gemini test connection** — same with a real Gemini Developer API key. _(P1.1b UI built; needs a key)_
- [ ] **J4 Default provider persists** — connect two providers, pick a default (radio), relaunch → selection holds. _(P1.1b UI built)_
- [ ] **J5 Gemma model download** — Settings → AI → AI providers → on-device card → Download model: runs on Wi-Fi only (unmetered), shows progress %, SHA-256 verifies, survives relaunch; Delete removes it. ~1.87 GB. _(P1.2b)_
- [ ] **J6 Gemma loads + generates** — after download, Test connection returns a reply. **Validate the text-only `gemma-4-E2B-it-web.litertlm` (WebGPU-tuned) actually loads on Android CPU via LiteRT-LM; if `Engine.initialize()` fails, switch `GemmaEngine.SPEC` to the standard `gemma-4-E2B-it.litertlm` (2.59 GB).** _(P1.2b)_
- [ ] **J7 On-device works offline** — with the model installed, Test connection (and later chat) returns a reply in airplane mode; confirm **no network traffic** during on-device generation. _(P1.2b)_
- [ ] **J8 RAM-floor gating** — on a <4 GB-RAM device the on-device card shows the "not enough memory" note and hides Download; on a ≥4 GB device Download is offered. Recommended-tier line reflects capability. _(P1.2b)_
- [ ] **J9 Tier degradation** — delete the Gemma model (or clear keys) → recommended tier falls back down Gemma→Nano→cloud→none with the card updating live. _(P1.2b)_
- [ ] **J10 Nano availability** — on a Nano-capable flagship (Pixel 9/Galaxy S25), the on-device card shows Nano status; on a non-flagship it shows "not supported" and degrades to Gemma/cloud (the `genai-prompt` dep must not crash non-flagships). _(P1.2c)_
- [ ] **J11 Enable Nano + generate** — when DOWNLOADABLE, "Enable Nano" downloads the feature (progress), then status → Available; Test connection returns a reply. _(P1.2c)_
- [ ] **J12 Preferred engine switch** — with BOTH Gemma installed and Nano available, the preferred-engine chips (Auto/Gemma/Nano) appear; switching changes which engine answers Test connection; Auto uses Gemma (default order). Persists across relaunch. _(P1.2c)_

## K. Chat & RAG (v2 / P2) _(SPEC-AI-PROVIDERS §6, docs/P2-PLAN.md)_
> CI covers the chunker/retriever (pure), chunk + chat DAOs/migrations (Robolectric),
> `ChatRepository` (fake provider + in-memory DB), and the redaction golden tests. What's
> left for hardware: real on-device generation, streaming feel, privacy-preview fidelity,
> retrieval relevance over a real library, and history persistence. Seeded for P2; each item
> activates when its subphase ships.
- [ ] **K1 Chunk embedding on device** — `EmbeddingWorker` chunk-indexes library papers on the unmetered job (eager backfill, bounded per run); `chunk_embeddings` fills (observable via `observeIndexedPaperCount`); a model change wipes mismatched chunks and re-indexes; the v1→v2 migration applies cleanly on an upgraded install. _(P2.1)_
- [ ] **K2 Retrieval relevance** — `RagRetriever` returns on-topic chunks for a real library question, scoped to a paper and to a collection; the hybrid blend (semantic cosine + `chunk_fts` BM25) beats either leg alone on a keyword-y vs paraphrase query (sanity, not a golden set). _(P2.1)_
- [ ] **K3 Streaming render** — Ask sheet renders tokens incrementally as they arrive (no freeze-then-dump); cancel mid-stream stops cleanly. _(P2.3)_
- [ ] **K4 Privacy preview fidelity** — before a **cloud** call the `ChatPreviewBuilder` "what leaves the device" confirm shows exactly the system instruction + messages (retrieved chunks folded in) that will be sent; **no provider key** (header-only), no gated note chunks (`includeNotes` off); on-device generation shows **no preview and emits no network traffic** (confirm in airplane mode). _(P2.2 builder / P2.3 sheet)_
- [ ] **K5 Provider resolution + fallback** — the explicitly selected provider is used by default; turning on **prefer-on-device-when-ready** uses an on-device engine when ready; an unconfigured selection falls back (on-device first); no usable provider surfaces a clear "configure a provider" state (`NotConfigured`). _(P2.2)_
- [ ] **K6 Summaries** — the summarize action returns a useful paper summary grounded in the abstract (and notes if present). _(P2.3)_
- [ ] **K7 Collection (KB) chat** — chat scoped to a collection retrieves across its papers; adding/removing a paper changes what's retrievable. _(P2.4)_
- [ ] **K8 Chat history persists** — sessions + turns survive app restart; per-paper and per-collection sessions resume. _(P2.4)_

## H. Success criteria rollup _(PRD §7)_
- [ ] **H1** New user can install, follow 2, and triage within 3 min of first launch (§7.1 — gated on A1/A2/B1).
- [ ] **H2** Hybrid search returns relevant results < 300ms, fully offline (§7.2 — C3/D2).
- [ ] **H3** A configured routine receives a well-formed payload and produces a useful run, no app-side errors (§7.3 — G2).
- [ ] **H4** Zero crashes in normal flows; CI green on every release commit (§7.4 — A5; CI half already holds).

---

## Verification log
Newest first. One entry per device session; absolute dates.

| Date | Build | Observed |
|---|---|---|
| 2026-06-14 | v1.2.1 (vc5) | Sync spinner now clears (I1 ✅ — refresh icon shown). Bottom blank strip above the nav bar **still present** on Today (and Paper Detail) — divider fix wasn't the cause. Root-caused to nested-Scaffold inset double-apply → fixed in v1.2.2. |
| 2026-06-14 | v1.2.0 (vc4) | **First fully-working device run.** Onboarding → Today, fetching new articles (B1), PDF light+dark (F1), tagging (F3), search (F4) all verified. Bugs found → fixed in v1.2.1: Today sync spinner never stops (I1), phantom empty row in lists + detail (I2/I3), and no way to add a paper to a collection (I4). |
| 2026-06-14 | v1.1.1 (vc3) | "Start reading" crashed again; **crash-reporter dialog worked** (A4 ✅) and the copied trace root-caused the DataStore/protobuf R8 casualty → fixed by disabling minification (ROADMAP UX.9, ships v1.2.0). Onboarding still never reached Today. |
| 2026-06-13 | v1.1.0 (vc2) | "Start reading" crashed; reopening showed onboarding again. Root-caused (statically, from the R8 mapping) to Hilt-multibinding stripping → R8 compat mode + keep rules (ROADMAP UX.8). |
| 2026-06-11 | v1.0.0 (vc1, self-IDed 0.1.0) | First signed release published; no device smoke recorded. |
