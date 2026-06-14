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

**Current target build: v1.2.0 (versionCode 4) — staged on `main`, tag not yet cut.**
No clean first-run has ever succeeded on a device (v1.1.0 and v1.1.1 both crashed at
onboarding); v1.2.0 is the first build expected to get past it.

---

## A. Release smoke / first run
- [ ] **A1 Install from scratch** — sideload the signed `arxiver-v<x>.apk` on a clean device (no prior data). Installs and launches to onboarding. _(was ROADMAP 5.8)_
- [ ] **A2 Onboarding → Today** — follow ≥2 categories, tap "Start reading", land on **Today** with no crash. _Never succeeded on device yet (crashed v1.1.0/v1.1.1)._
- [ ] **A3 Onboarded flag persists** — kill and reopen; app opens on Today, not onboarding.
- [x] **A4 Crash reporter** — an uncaught exception saves a trace; next launch shows a copyable dialog. _Verified v1.1.1 (2026-06-14): user copied the protobuf trace. From v1.2.0 traces are de-obfuscated (real names)._
- [ ] **A5 Zero crashes in normal flows** — exercise each tab without a crash. _(PRD §7.4)_

## B. Background sync & workers
> Critical and never verified on any installed release: all four `@HiltWorker` factories were
> R8-stripped from v1.0.0–v1.1.1, so background work was uninstantiable. v1.2.0 (minify off)
> is the first build where these can actually run.
- [ ] **B1 FollowSyncWorker** — followed categories populate the Today inbox after first sync.
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
- [ ] **F1 PDF** — download + in-app reader: night invert, paging, page pill.
- [ ] **F2 Deep links / share-in** — opening or sharing an arxiv.org URL resolves to the paper detail.

## G. Claude dispatch (live — needs the user's routines) _(ROADMAP 6.6)_
- [ ] **G1 Guided setup wizard** — create on claude.ai → connect URL+token (live validation) → opt-in verify ping against a real routine. (Fire-API contract itself was live-verified in dev, task 4.8.)
- [ ] **G2 End-to-end** — set up ≥2 catalog templates via the wizard and confirm real runs; record findings in SPEC-CLAUDE-BRIDGE.
- [ ] **G3 Privacy preview** — the confirm sheet previews exactly what leaves the device; tokens never appear (redaction is structurally tested — confirm visually).

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
| 2026-06-14 | v1.1.1 (vc3) | "Start reading" crashed again; **crash-reporter dialog worked** (A4 ✅) and the copied trace root-caused the DataStore/protobuf R8 casualty → fixed by disabling minification (ROADMAP UX.9, ships v1.2.0). Onboarding still never reached Today. |
| 2026-06-13 | v1.1.0 (vc2) | "Start reading" crashed; reopening showed onboarding again. Root-caused (statically, from the R8 mapping) to Hilt-multibinding stripping → R8 compat mode + keep rules (ROADMAP UX.8). |
| 2026-06-11 | v1.0.0 (vc1, self-IDed 0.1.0) | First signed release published; no device smoke recorded. |
