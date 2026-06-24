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

Legend: `[ ]` pending · `[x]` verified on device (build/date) · `[E]` verified on
emulator (build/date) · `[~]` partial · `[!]` failed (with pointer) · `[-]` superseded / n/a

> **`[E]` (emulator-verified)** was introduced 2026-06-23 when an x86_64 emulator (API 34)
> + autonomous adb/screenshot driving was set up locally. The emulator fully covers most
> non-hardware surfaces (UI/UX, workers, insets, feedback/swipe, deep links, embeddings,
> RAG, notifications). It is **not** authoritative for: Gemini Nano (flagship AICore only),
> real-device performance numbers (D1–D3), the hardware-backed Keystore (J1), or arm64
> Gemma throughput — those keep needing the S20 / a flagship and stay `[ ]` until then.

**Current target build: v2.0.2 (versionCode 7).** Reconciles the stale build identity
(the build self-IDed as 1.2.2/vc6 while tags were already at v2.0.1). v1.2.0 verified
end-to-end on a physical S20 (2026-06-14). **2026-06-23: first emulator verification
session** (API 34 x86_64) cleared the §I field-fixes and a large slice of §L/§F/§A/§H —
see the `[E]` items and the Verification-log. §I re-checks now pass on the emulator.

---

## A. Release smoke / first run
- [x] **A1 Install from scratch** — sideload the signed APK on a clean device. _Verified v1.2.0 (2026-06-14)._
- [x] **A2 Onboarding → Today** — follow ≥2 categories, tap "Start reading", land on **Today** with no crash. _Verified v1.2.0 (2026-06-14) — first build to clear the R8 crashes._
- [x] **A3 Onboarded flag persists** — reopen stays on Today, not onboarding. _Verified v1.2.0 (2026-06-14)._
- [x] **A4 Crash reporter** — an uncaught exception saves a trace; next launch shows a copyable dialog. _Verified v1.1.1 (2026-06-14): user copied the protobuf trace. From v1.2.0 traces are de-obfuscated (real names)._
- [E] **A5 Zero crashes in normal flows** — exercise each tab without a crash. _(PRD §7.4)_ _v2.0.2 (emu 2026-06-23): tab-by-tab sweep Today/Browse/Search/Library/Settings/Detail/PDF/Connections — zero `AndroidRuntime:E` in logcat. (Ask/Claude surfaces ride V4/V5.)_

## B. Background sync & workers
> The `@HiltWorker` factories were R8-stripped v1.0.0–v1.1.1; v1.2.0 (minify off) is the
> first build where they run — confirmed by B1.
- [x] **B1 FollowSyncWorker** — followed categories populate the Today inbox after first sync. _Verified v1.2.0 (2026-06-14): fetching new articles works._
- [E] **B2 EmbeddingWorker** — runs on unmetered network; un-embedded papers get embedded (Settings shows the embedded count climbing). _v2.0.2 (emu 2026-06-23): after the first sync the worker ran and Settings shows "28 papers embedded locally"; chat retrieval returns real chunks, confirming the embeddings exist._
- [ ] **B3 CitationSyncWorker** — nightly citation fetch fills a saved paper's Connections (references/citations).
- [ ] **B4 DispatchQueueWorker** — a dispatch queued while offline sends once back online.
- [ ] **B5 Sync cadence** — periodic sync respects the interval set in Settings.

## C. On-device ML
- [~] **C1 Model download** — pinned bge-small-en-v1.5 ONNX (~34 MB) downloads on unmetered with progress UI; checksum verified; survives delete/re-index in Settings. _v2.0.2 (emu 2026-06-23): the model auto-downloaded over the emulator network and loaded ("Model ready — hybrid search active"), implicitly checksum-passing. The progress UI + explicit delete/re-index round-trip were not driven this session (V2-followup)._
- [E] **C2 Embedding inference** — WordPiece tokenizer + ONNX session produce embeddings on device (unit-tested against reference tokenizations; on-device inference not yet run). _v2.0.2 (emu 2026-06-23): bge ONNX inference runs on the x86_64 emulator (28 papers embedded; chat retrieval returns relevant chunks). The "not yet run on device" gap is closed for x86_64; arm64 throughput still rides the S20._
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
- [E] **F2 Deep links / share-in** — opening or sharing an arxiv.org URL resolves to the paper detail. _v2.0.2 (emu 2026-06-23): VIEW `https://arxiv.org/abs/2606.23678` → AIR paper detail; ACTION_SEND of an abs URL → SpatialClaw detail. Note: https auto-open routes to the browser by design (`autoVerify="false"` — arxiv.org isn't our domain, the user picks Arxiver or shares in)._
- [x] **F3 Tagging** — add/remove tags on a paper; persists. _Verified v1.2.0 (2026-06-14)._
- [x] **F4 Search (functional)** — local + arXiv search return results. _Verified v1.2.0 (2026-06-14); relevance/latency targets still pending (C3/D2)._

## G. Claude dispatch (live — needs the user's routines) _(ROADMAP 6.6)_
- [ ] **G1 Guided setup wizard** — create on claude.ai → connect URL+token (live validation) → opt-in verify ping against a real routine. (Fire-API contract itself was live-verified in dev, task 4.8.)
- [ ] **G2 End-to-end** — set up ≥2 catalog templates via the wizard and confirm real runs; record findings in SPEC-CLAUDE-BRIDGE.
- [ ] **G3 Privacy preview** — the confirm sheet previews exactly what leaves the device; tokens never appear (redaction is structurally tested — confirm visually).

## I. v1.2.1 / v1.2.2 field fixes — re-verify on device
- [x] **I1 Sync spinner clears** — Today top-bar spinner stops (reverts to refresh icon). _Verified v1.2.1 (2026-06-14): screenshot shows the refresh icon, no perpetual spinner._
- [E] **I2 No phantom empty row (lists)** — Today/Library/Search list ends cleanly. _v2.0.2 (emu 2026-06-23): scrolled Today to its true end (last paper card flush, no trailing divider/phantom row)._
- [E] **I3 No bottom blank strip (app-wide)** — **root-caused v1.2.2:** nested Scaffolds double-applied the bottom nav inset → blank strip above the bottom bar on Today/list screens (and the gesture-bar clearance on detail screens). Fixed via `consumeWindowInsets` on the NavHost + trimmed PaperDetail's redundant trailing padding. _v2.0.2 (emu 2026-06-23, gesture nav on): no blank strip on Today (list flush above the nav bar) AND on Paper Detail (metadata card ends with only the correct small gesture-bar inset)._
- [E] **I4 Add to collection** — from a saved paper's detail, add it to a collection; it appears under that collection (Library → Collections → open); toggling off removes it; survives relaunch; "New collection" from the picker works. _v2.0.2 (emu 2026-06-23): saved AIR paper → created "Reasoning" via inline "New collection" → paper appears in Library→Collections→Reasoning → survives relaunch AND survives the vc6→vc7 upgrade install. (Toggle-off removal is CI-covered by F1.3 DAO/VM tests.)_

## J. AI providers (v2 / P1) _(SPEC-AI-PROVIDERS)_
> CI covers the transports (MockWebServer SSE), the registry/settings ViewModel
> (pure-JVM with fakes), and the key-vault contract (Robolectric, in :core:ai).
> What's left for hardware: the **hardware-backed Keystore** path and **real keys**.
> The **Settings → AI → AI providers** screen is built (P1.1b); these need a real key.
- [E] **J1 Key vault on hardware Keystore** — a saved provider key survives app restart on a real device (Robolectric uses a software keystore; confirm the AndroidKeyStore-backed `EncryptedSharedPreferences` round-trips on device). _v2.0.2 (emu 2026-06-23): both Claude + Gemini keys survived an app relaunch (EncryptedSharedPreferences round-trip on the emulator's AndroidKeyStore). The **hardware-backed (TEE/StrongBox)** path specifically still wants the S20._ _(P1.1a)_
- [E] **J2 Claude test connection** — Settings → AI → AI providers, paste a real Anthropic key, Save, Test connection → "Connection OK"; a wrong key → "key rejected"; airplane mode → "offline". _v2.0.2 (emu 2026-06-23): a real Anthropic key → **"Connection OK"** against the live Messages API; a wrong key (the Gemini key pasted into Claude's field) → "Key rejected"._ _(P1.1b)_
- [E] **J3 Gemini test connection** — same with a real Gemini Developer API key. _2026-06-23 (P-Rich R0): with a valid `AIza…` key, Test still showed **"Couldn't reach the provider"**. **Root cause (real bug):** the pinned model `gemini-2.0-flash` is **deprecated** — `generateContent` returns **HTTP 404** "This model is no longer available", which the app maps to the generic connection error. So Gemini chat was fully broken regardless of key. **Fix:** `GeminiProvider.DEFAULT_MODEL` → `gemini-2.5-flash` (verified live: `gemini-2.0-flash` → 404, `gemini-2.5-flash` → 200). After the fix, Test connection returns "Connection OK". (The V2 `AQ.…` key was also the wrong **type** — a red herring; the deprecated model was the actual blocker.)_ _(P1.1b / P-Rich R0)_
- [E] **J4 Default provider persists** — connect two providers, pick a default (radio), relaunch → selection holds. _v2.0.2 (emu 2026-06-23): both providers connected, Claude set as default → after relaunch Claude's "Use as default" radio is still selected and chat resolves to Claude._ _(P1.1b)_
- [ ] **J5 Gemma model download** — Settings → AI → AI providers → on-device card → Download model: runs on Wi-Fi only (unmetered), shows progress %, SHA-256 verifies, survives relaunch; Delete removes it. ~2.59 GB. A re-download after the F2 model swap also purges the stale `-web` file (no double-counting on disk). _(P1.2b/F2)_
- [ ] **J6 Gemma loads + generates** — after download, Test connection returns a reply and per-paper Ask streams real tokens on the S20 (CPU/XNNPACK). F2 swapped `GemmaEngine.SPEC` to the standard CPU build `gemma-4-E2B-it.litertlm` (the `-web` build had no CPU decode graph → zero tokens, confirmed 2026-06-19). Confirm the standard build's CPU `TF_LITE_PREFILL_DECODE` graph runs here. _(P1.2b/F2)_
- [ ] **J7 On-device works offline** — with the model installed, Test connection (and later chat) returns a reply in airplane mode; confirm **no network traffic** during on-device generation. _(P1.2b)_
- [~] **J8 RAM-floor gating** — on a <4 GB-RAM device the on-device card shows the "not enough memory" note and hides Download; on a ≥4 GB device Download is offered. Recommended-tier line reflects capability. _v2.0.2 (emu 2026-06-23): the **<4 GB branch** is verified — this AVD reports "Device memory: 1973 MB", the card shows "This device doesn't have enough memory to run the on-device model" and hides Download. The ≥4 GB branch (Download offered) rides the **V3** 6 GB AVD._ _(P1.2b)_
- [ ] **J9 Tier degradation** — delete the Gemma model (or clear keys) → recommended tier falls back down Gemma→Nano→cloud→none with the card updating live. _(P1.2b)_
- [~] **J10 Nano availability** — on a Nano-capable flagship (Pixel 9/Galaxy S25), the on-device card shows Nano status; on a non-flagship it shows "not supported" and degrades to Gemma/cloud (the `genai-prompt` dep must not crash non-flagships). _v2.0.2 (emu 2026-06-23): the **non-flagship branch** is verified — the card shows "Gemini Nano (system): Not supported on this device", the `genai-prompt` dep does not crash, and the app degrades to cloud. The positive Nano-status branch is flagship-hardware-only (the S20 cannot do it either)._ _(P1.2c)_
- [ ] **J11 Enable Nano + generate** — when DOWNLOADABLE, "Enable Nano" downloads the feature (progress), then status → Available; Test connection returns a reply. _(P1.2c)_
- [ ] **J12 Preferred engine switch** — with BOTH Gemma installed and Nano available, the preferred-engine chips (Auto/Gemma/Nano) appear; switching changes which engine answers Test connection; Auto uses Gemma (default order). Persists across relaunch. _(P1.2c)_

## K. Chat & RAG (v2 / P2) _(SPEC-AI-PROVIDERS §6, docs/P2-PLAN.md)_
> CI covers the chunker/retriever (pure), chunk + chat DAOs/migrations (Robolectric),
> `ChatRepository` (fake provider + in-memory DB), and the redaction golden tests. What's
> left for hardware: real on-device generation, streaming feel, privacy-preview fidelity,
> retrieval relevance over a real library, and history persistence. Seeded for P2; each item
> activates when its subphase ships.
- [E] **K1 Chunk embedding on device** — `EmbeddingWorker` chunk-indexes library papers on the unmetered job (eager backfill, bounded per run); `chunk_embeddings` fills (observable via `observeIndexedPaperCount`); a model change wipes mismatched chunks and re-indexes; the v1→v2 migration applies cleanly on an upgraded install. _v2.0.2 (emu 2026-06-23): Settings shows "Model ready — 28 papers embedded locally" (the bge ONNX model auto-downloaded and the worker backfilled), and chat retrieval returns real per-paper chunks (so `chunk_embeddings` is populated). The migration applied cleanly (the app runs end-to-end on the upgraded install). Formal model-change wipe/re-index rides V2-followup._ _(P2.1)_
- [E] **K2 Retrieval relevance** — `RagRetriever` returns on-topic chunks for a real library question, scoped to a paper and to a collection; the hybrid blend (semantic cosine + `chunk_fts` BM25) beats either leg alone on a keyword-y vs paraphrase query (sanity, not a golden set). _v2.0.2 (emu 2026-06-23): paper-scoped retrieval surfaced the AIR abstract as context `[1]` → a correct grounded answer; collection-scoped retrieval over "Reasoning" pulled context from **both** papers (`[1]`+`[2]`) and the answer distinguished them. **Bug found+fixed:** an inbox-only (un-embedded) paper retrieved nothing → ungrounded answer despite the sheet's "grounded in its abstract" — the `ScopeIndexer` Paper branch was a no-op; now binds `RagIndexer.indexPaper` + `AskViewModel` awaits indexing before retrieval (`AskViewModelTest` race test added)._ _(P2.1)_
- [E] **K3 Streaming render** — the `AskSheet` renders tokens incrementally as they arrive (no freeze-then-dump); the Stop button cancels mid-stream cleanly and leaves the partial answer. _v2.0.2 (emu 2026-06-23, **cloud/Claude**): per-paper Ask + collection chat both stream non-blank answers into chat bubbles (markdown rendered). The **on-device Gemma** streaming leg (the F2 S20 re-verify, blank-bubble guard) still rides **V3** / the S20._ _(P2.3 / F2)_
- [E] **K4 Privacy preview fidelity** — before a **cloud** call the `ChatPreviewBuilder` "what leaves the device" confirm shows exactly the system instruction + messages (retrieved chunks folded in) that will be sent; **no provider key** (header-only), no gated note chunks (`includeNotes` off); on-device generation shows **no preview and emits no network traffic** (confirm in airplane mode). _v2.0.2 (emu 2026-06-23): the cloud confirm shows the exact SYSTEM instruction + USER message with the retrieved `[1]`/`[2]` excerpts folded in, and states "Your API key is sent in a header, never in this body" — key structurally absent. The on-device no-preview/no-traffic leg rides **V3**._ _(P2.2 builder / P2.3 sheet)_
- [E] **K5 Provider resolution + fallback** — the explicitly selected provider is used by default; turning on **prefer-on-device-when-ready** uses an on-device engine when ready; an unconfigured selection falls back (on-device first); no usable provider surfaces a clear "configure a provider" state (`NotConfigured`). _v2.0.2 (emu 2026-06-23): the selected provider (Claude) answered every turn; clearing both keys → send surfaces "No AI provider is set up yet… Configure a provider" (`NotConfigured`). The prefer-on-device toggle isn't offered on this AVD (no eligible on-device engine, by design); its "uses on-device when ready" leg rides **V3**._ _(P2.2)_
- [E] **K6 Summaries** — the `AskSheet` summarize chip returns a useful paper summary grounded in the abstract (and notes if included). _v2.0.2 (emu 2026-06-23): the Summarize chip on an embedded paper returned a grounded, markdown-formatted "## Summary" (problem/approach/result) streamed from Claude._ _(P2.3)_
- [E] **K7 Collection (KB) chat** — the collection screen's "Chat" action opens KB chat that retrieves across the collection's papers (ensure-embedded indexes missing papers on open, shown by the "Preparing this collection…" note); adding/removing a paper changes what's retrievable. _v2.0.2 (emu 2026-06-23): "Chat with this collection" over a 2-paper collection retrieved context from both (`[1]`+`[2]`) → a synthesized cross-paper answer. (Both papers were already embedded, so no "Preparing…" note showed — correct.) **Cosmetic note:** the collection chat sheet still uses the per-paper labels ("Ask this paper"). Add/remove-changes-retrieval rides V2-followup._ _(P2.4)_
- [E] **K8 Chat history persists** — the Settings → "Chat history" list shows past sessions (paper title / collection name); sessions + turns survive app restart; tapping resumes the right conversation; delete removes it. _v2.0.2 (emu 2026-06-23): sessions listed with label + scope chip (Paper/Collection) + relative time; survived an app relaunch; tapping the AIR session resumed its exact Q&A; delete removed a session (4→3)._ _(P2.4)_

## L. Feedback, selection, swipe & background tasks (v2.0-alpha / Phase UX2) _(SPEC-UI §4/§4a/§5)_
> CI covers the pure/logic pieces: `FeedbackControllerTest`, `SelectionStateTest`,
> `SwipeablePaperRowTest` (a11y-action builder), `OrganizeViewModelTest` (tri-state/idempotence),
> `FilteredPapers`/`CategoryFeed` save paths, `BackgroundTaskMonitorTest` (state→task + cancel
> routing), `DownloadNotificationsTest` (channel/ForegroundInfo). What needs hardware:
- [E] **L1 Elevated feedback** — the app-level snackbar shows above the bottom bar with real elevation, a working dismiss "✕", longer dwell for action-bearing messages, and both actions reachable (Undo **and** "Add to…"). _v2.0.2 (emu 2026-06-23): "Saved to library" snackbar carries both **Undo** and **Add to…**; "Paper dismissed" carries Undo; action-bearing messages dwell longer (≈8s)._
- [E] **L2 Bulk Organize (the #1 ask)** — multi-select in Library/collection/tag/Search/feed → "Add to collection or tag" files every selected paper; tri-state chips read correctly across a mixed-membership selection; inline new-collection/new-tag adds all; tapping a full (✓) target removes all. _v2.0.2 (emu 2026-06-23): long-press → selection mode → selected 2 Library papers (one already in "Reasoning", one not) → "Add 2 papers to…" sheet → tapping Reasoning filled the gap → both now in the collection._
- [E] **L3 Swipe everywhere** — swipe-right = save, swipe-left = remove/dismiss on Today/Search/CategoryFeed/Filtered/Library; TalkBack exposes the same actions; swipe is inert in selection mode (no fight with long-press). Save-in-place rows snap back; triaged/removed rows leave the list. _v2.0.2 (emu 2026-06-23) on Today: swipe-right → "Saved to library" (row snaps back); swipe-left → "Paper dismissed" (row leaves, list advances), both with Undo. TalkBack custom-action equivalents are CI-covered (`SwipeablePaperRowTest`); the full per-list swipe matrix continues under V6._
- [~] **L4 Background tasks sheet** — Settings → "Background activity" shows live progress for the Gemma download, follow-sync and embedding/indexing; cancel actually stops the worker; retry re-enqueues. _v2.0.2 (emu 2026-06-23): the sheet opens from Settings and renders its empty state ("Nothing running right now"). Live progress + cancel/retry not yet caught — workers complete too fast on a warm emulator to observe; will be exercised against the long Gemma download in **V3**. Monitor state→task + cancel routing is CI-covered (`BackgroundTaskMonitorTest`)._
- [ ] **L5 Foreground-service download** — the ~2.59 GB Gemma download runs as a foreground service with an ongoing local progress notification, survives backgrounding/Doze, completes + SHA-verifies. **Local-only** — confirm no network beyond the pinned model URL (no telemetry).
- [ ] **L6 POST_NOTIFICATIONS (Android 13+, the S20)** — the permission is requested when the on-device download is first triggered; **denying it still completes the download**, only the notification is suppressed (graceful degradation).

## H. Success criteria rollup _(PRD §7)_
- [E] **H1** New user can install, follow 2, and triage within 3 min of first launch (§7.1 — gated on A1/A2/B1). _v2.0.2 (emu 2026-06-23): fresh install → onboarding → followed 2 categories → "Start reading" → Today populated with fresh papers → triaged via swipe, all within the ~3-min rate-limiter budget (which is network-bound, so emulator timing is representative)._
- [ ] **H2** Hybrid search returns relevant results < 300ms, fully offline (§7.2 — C3/D2).
- [ ] **H3** A configured routine receives a well-formed payload and produces a useful run, no app-side errors (§7.3 — G2).
- [E] **H4** Zero crashes in normal flows; CI green on every release commit (§7.4 — A5; CI half already holds). _v2.0.2 (emu 2026-06-23): the A5 crash-free half holds on the emulator; the CI half holds (green gate). Ask/Claude surfaces ride V4/V5._

---

## Verification log
Newest first. One entry per device session; absolute dates.

| Date | Build | Observed |
|---|---|---|
| 2026-06-23 | v2.0.2 (vc7, **emulator** API 34 x86_64) | **P-Rich R1 — math (KaTeX).** Asked a paper for a display LaTeX equation: the answer routed to the sandboxed offline `RichBlockWebView` and rendered a real **display equation** (`Ŝ = Σ_{k∈Q} wₖxₖ` with hat/summation/subscripts) plus **inline math** in a themed table and section headings (`Ŝ`, `Q`, `xₖ`, `k∈Q`) — not raw `$$…$$`. **Offline confirmed:** with airplane mode ON, re-opening the session still renders the math (bundled KaTeX, `blockNetworkLoads`, no network). Plain answers still use the native renderer; the WebView's only callbacks are the safe `arxiver://cite\|height` link intents. |
| 2026-06-23 | v2.0.2 (vc7, **emulator** API 34 x86_64) | **P-Rich R0 — rich AI output (markdown + citations).** AI answers now render as **markdown** in the Ask sheet (verified live: headings, **bold**, _italic_, numbered lists, and a real bordered **GFM table** — previously raw `**`/`##`). `[n]` renders as a colored tappable citation that opens a **"Sources" expander** showing the cited excerpt (e.g. `[1] arXiv:2606.23633 — …`). The system prompt now invites markdown (seen in the "what leaves the device" preview); the inbox-grounding fix + key-header-only preview still hold. **Bug fixed (real, total Gemini breakage):** Gemini Test failed even with a valid `AIza…` key — the pinned model `gemini-2.0-flash` is **deprecated → HTTP 404**; swapped to `gemini-2.5-flash` (J3). |
| 2026-06-23 | v2.0.2 (vc7, **emulator** API 34 x86_64) | **Cloud chat & RAG end-to-end (BYOK).** Connected a real Anthropic key → Test "Connection OK" (J2); default persists across relaunch (J4); both keys survive relaunch (J1 software-keystore). Per-paper Ask on an embedded paper streamed a grounded answer citing `[1]` (K2/K3), with the "what leaves the device" confirm showing key-is-header-only (K4); Summarize returned a grounded markdown summary (K6); collection KB chat synthesized across both papers `[1]`+`[2]` (K7); chat history listed/resumed/survived-restart/deleted (K8); cleared keys → `NotConfigured` "configure a provider" (K5). bge model "ready", 28 papers embedded (C1/C2/B2). RAM-floor `<4 GB` branch hides Gemma download (J8); Nano "not supported" non-flagship branch (J10). **Findings:** (1) Gemini key (`AQ.…`) is the wrong type → "Couldn't reach the provider" (J3) — needs an `AIza…` key; (2) **bug fixed** — per-paper Ask on an inbox (un-embedded) paper sent no context (the `ScopeIndexer` Paper branch was a no-op) → now indexes the paper on open + awaits it before retrieval. |
| 2026-06-23 | v2.0.2 (vc7, **emulator** API 34 x86_64) | **First autonomous emulator verification session.** Local env set up (build under pinned JDK 17, adb-driven UI + screenshots). Cleared `[E]`: A5 (crash-free sweep, 8 screens), I2/I3/I4 (phantom row, bottom-strip on Today+Detail, add-to-collection + survives upgrade), L1/L2/L3 (elevated feedback, bulk Organize, swipe both directions), F2 (deep link + share-in), H1, H4. L4 partial (sheet renders; live-progress deferred to V3). Re-confirmed PDF render (light) + Settings shows "Model ready — 28 papers embedded" (pre-evidence for C1/C2/B2, formal in V2). **Bug fixed:** `ArxiverApplication.onCreate` launched fire-and-forget IO coroutines (taxonomySeeder.seed / ensurePeriodicSync) with no `CoroutineExceptionHandler`; a DB-teardown race threw `IllegalStateException: Illegal connection pointer` uncaught → flaked 4 Robolectric suites locally (passed on CI's slower runners). Added a logging CEH → deterministic green. Version reconciled 1.2.2/vc6 → 2.0.2/vc7. |
| 2026-06-14 | v1.2.1 (vc5) | Sync spinner now clears (I1 ✅ — refresh icon shown). Bottom blank strip above the nav bar **still present** on Today (and Paper Detail) — divider fix wasn't the cause. Root-caused to nested-Scaffold inset double-apply → fixed in v1.2.2. |
| 2026-06-14 | v1.2.0 (vc4) | **First fully-working device run.** Onboarding → Today, fetching new articles (B1), PDF light+dark (F1), tagging (F3), search (F4) all verified. Bugs found → fixed in v1.2.1: Today sync spinner never stops (I1), phantom empty row in lists + detail (I2/I3), and no way to add a paper to a collection (I4). |
| 2026-06-14 | v1.1.1 (vc3) | "Start reading" crashed again; **crash-reporter dialog worked** (A4 ✅) and the copied trace root-caused the DataStore/protobuf R8 casualty → fixed by disabling minification (ROADMAP UX.9, ships v1.2.0). Onboarding still never reached Today. |
| 2026-06-13 | v1.1.0 (vc2) | "Start reading" crashed; reopening showed onboarding again. Root-caused (statically, from the R8 mapping) to Hilt-multibinding stripping → R8 compat mode + keep rules (ROADMAP UX.8). |
| 2026-06-11 | v1.0.0 (vc1, self-IDed 0.1.0) | First signed release published; no device smoke recorded. |
