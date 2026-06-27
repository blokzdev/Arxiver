# CLAUDE.md — Arxiver Engineering & Workflow Guide

Guidance for Claude Code sessions working in this repo. The project is built documentation-first and roadmap-driven; this file defines the self-governing loop.

## What this project is

Local-first Android arXiv explorer (Kotlin + Jetpack Compose) with on-device hybrid search and a Claude Routines dispatch bridge. Read in order if new to the repo: `docs/PRD.md` → `docs/ARCHITECTURE.md` → the SPEC for whatever you're touching → `ROADMAP.md`.

## Operating model (Ultracode orchestration + adversarial validation)

Claude (Opus 4.8, Ultracode mode) runs this repo as **PM + tech lead and the accountable owner**: it orchestrates multi-agent **Workflows** for research, mapping, design, and adversarial testing, then personally validates and ships. *Agents draft; Claude decides and owns the result* — never ship an agent's output unverified.

**Planning ritual — for every phase and subphase, before code:**

1. **Orchestrate** a Workflow: map the affected code + specs → diverse design options → an adversarial judge/stress pass (hunt failure modes, edge cases, hidden coupling) → synthesis into one plan.
2. **Personal adversarial validation (Claude's own pass — never delegated):** open the *actual* files and verify the plan's load-bearing claims (don't trust the agents' summaries); attack the riskiest decisions; hunt loose ends, gaps, and unstated assumptions.
3. **Refine & maximize in-scope:** fold in quick wins, close gaps, drop redundant/dead steps, strengthen the plan to its best form *within scope*; defer genuinely new scope to its own ROADMAP item.
4. **Approve, then build.** A **new phase's** plan goes to the user for approval (plan mode). **Within an approved phase, Claude self-approves each subphase** after steps 1–3 and ships it per the loop below — the human is kept in the loop *asynchronously via `HUMAN.md`*, not a per-subphase gate. The standing blocker is **phase-plan approval, not merge**.

**Scale the ceremony to risk:** high-risk / irreversible / cross-cutting → full Workflow + a hard adversarial pass; low-risk / local → a lighter pass; trivial-mechanical, conversational, or self-governance → solo (don't convene a committee to fix a typo). Step 2 (the personal adversarial pass) is **never skipped on anything load-bearing**; its depth scales. When the user turns **Ultracode off**, revert to the lean loop (solo implementation, single build-gate verification, normal PR) and re-enable when complexity warrants.

## The loop (checkpoint protocol)

Every working session, and repeatedly within a session:

1. **Orient:** read `ROADMAP.md` and the memory index `.claude/memory/MEMORY.md` (see Memory harness below); find the first `[~]` or `[ ]` task. Never skip ahead across an unmet CHECKPOINT. Also skim `VERIFICATION.md` (the on-device ledger) when touching anything device-observable — release behavior, workers, ML, performance, a11y, PDF, live dispatch.
2. **Mark:** set the task `[~]` (in progress).
3. **Read the spec** section governing the task before writing code. Specs are the source of truth; if implementation must deviate, update the spec in the same commit and note it in the Decision log (ROADMAP bottom).
4. **Implement** with tests per the spec's testing section.
5. **Verify:** `./gradlew build` (compiles + unit tests + lint) must pass. Never commit red.
6. **Record:** mark task `[x]`, update Decision log if decisions were made.
7. **Commit & push:** one commit per task (or tight task cluster), descriptive message, push to the subphase branch (`git push -u origin <branch>`; on network failure retry ×4 with 2/4/8/16s backoff).
8. **Ship the PR (autonomous loop):** open a ready-for-review PR. **Once CI is green, merge it** (no waiting on the user), then return to step 1 — plan the next subphase via the **Operating model** ritual above (Workflow → personal adversarial validation → self-approve *within an approved phase*; a **new phase** goes to the user in plan mode). The standing blocker is *phase-plan approval*, not merge. **Bounce back to the user** (don't merge / don't push further) when: there are unresolved review comments, CI fails or is flaky again after a reasonable re-kick, you're genuinely unsure of a fix, or a red-line / host / security / cost / irreversible decision is involved (log it in `HUMAN.md`). Keep the local `./gradlew build` green before pushing — CI is the merge gate, not a substitute for local verification.
9. **Checkpoint gates:** at a phase CHECKPOINT, run the full phase verification listed in ROADMAP, fix anything failing, then check the checkpoint box in its own commit titled `checkpoint: phase N`.
10. Tasks tagged `[needs-user]` (e.g. real routine token): ask the user, mark `[!] blocked` with reason if no answer, and continue with the next non-dependent task.
11. **Device-bound work → `VERIFICATION.md`:** if a task's only remaining verification needs real hardware (signed-APK behavior, background workers, on-device ML, performance, TalkBack, PDF, live routine dispatch), the task can still go `[x]` on a green build — but add/refresh its concrete check in `VERIFICATION.md` (mirroring any `[needs-user/device]` ROADMAP item) in the same commit. When the user reports a device session, update the matching item's status and append a dated Verification-log row, again in the same commit.

Rules: no `[x]` without green build · ROADMAP edits travel in the same commit as the work · device-only verification never blocks `[x]` but must be tracked in `VERIFICATION.md` · blocked ≠ stopped (find the next unblocked task) · when context is summarized mid-session, re-orient from step 1.

## Memory harness (continuity across environments)

`.claude/memory/` is versioned agent memory — the continuity channel across local machines, cloud/mobile sessions, and collaborators. The repo is the only state every environment shares, so durable working context lives here, never in a machine-local store. `MEMORY.md` is the index: one line per memory (`- [Title](file.md) — hook`). Each memory is one file holding one fact:

```markdown
---
name: short-kebab-slug
description: one-line summary used to judge relevance
type: project | process | gotcha | reference
---

The fact. Absolute dates only. Link related memories with [[name]].
```

- **Read at orient time:** scan the index every session; open any memory whose hook touches the task at hand.
- **Self-healing — memory you use, you verify:** if a memory contradicts the repo or reality, fix or delete it (file + index line) in the same commit as your work; if the index and the files drift apart, rebuild the index. Verify concrete claims (paths, task numbers, URLs) before acting on them.
- **Write when it saves a future session real work:** process constraints, environment gotchas, external-service contract details, state of external systems (CI, releases, secret *names*).
- **Never record** what the repo already states (code, specs, ROADMAP, git history) or anything sensitive — no tokens, passwords, or personal user context. The Red lines section applies to memory files in full.
- **Memory ≠ roadmap:** tasks and progress belong in `ROADMAP.md`; memory holds the facts and context tasks rely on. Memory edits travel in the same commit as the work that made them true.

## HUMAN.md — keeping the Co-Founder in the loop

`HUMAN.md` at the repo root is the **asynchronous channel to the human Co-Founder**, so the autonomous loop never silently outruns them. It holds only what a human needs to *see or do*: **decisions/judgment calls** Claude made autonomously (auditable + overridable), **things only the human can do** (device/manual verification, secrets, releases, external accounts, the kind of red-line/cost call flagged in loop step 8), **open questions**, and deliberate **carve-outs/deferrals**. It is **not** a status board (`ROADMAP.md` owns progress) or a fact store (`.claude/memory/` owns durable facts) — point at those, never duplicate. Add an entry in the **same commit** as the work that created it; resolve (don't silently delete) rows when met. Setup/env lives in `.claude/memory/` — no separate `SETUP.md`.

## Commands

```bash
./gradlew build                  # full check: compile, unit tests, lint — the gate
./gradlew :app:assembleDebug     # debug APK
./gradlew test                   # unit tests only
./gradlew lint ktlintCheck       # static analysis
./gradlew :core:database:test    # fastest loop for DB/schema work (Robolectric)
```

Environment note: cloud sessions need the Android SDK; if `sdkmanager` is absent, install command-line tools to `$HOME/android-sdk`, accept licenses, set `ANDROID_HOME`, and write `local.properties` (`sdk.dir=...`). Keep `local.properties` untracked.

## Conventions

- **Layering:** UI (Compose, dumb) → ViewModel (UiState/UiEvent) → repository/use-case → core modules. No layer-skipping imports; `:core:*` modules never depend on `:app`.
- **Errors:** sealed `AppResult<T>` across layer boundaries; exceptions don't cross modules.
- **Async:** coroutines/Flow only. Injected dispatchers (never hardcode `Dispatchers.IO` in logic — inject for testability).
- **DB:** every schema change = Room `Migration` + test; destructive migration is forbidden; schema JSONs committed under `core/database/schemas/`.
- **Serialization:** kotlinx.serialization. Payload schema changes require golden-file test updates + SPEC-CLAUDE-BRIDGE edit + `schema` version bump if breaking.
- **Style:** ktlint-default Kotlin style; Compose naming per official guidelines (PascalCase composables, `Modifier` first optional param). Comments only where the code can't speak (rate-limit contracts, fusion math rationale).
- **Strings** user-facing → `strings.xml` from day one. No hardcoded UI text.
- **Fixtures:** real arXiv Atom XML + abstracts under `core/network/src/test/resources/` and shared fixture objects in `:core:model` test-fixtures; UI previews reuse them.

## Red lines (security/privacy)

- Routine tokens: only ever in `EncryptedSharedPreferences` via `TokenVault`. Never in DB, logs, payload history, exports, backups, or test fixtures. Structural tests enforce payload redaction — keep them passing.
- No analytics/telemetry dependencies. Network calls only to: export.arxiv.org, api.semanticscholar.org, user-configured routine URLs, and the pinned model-download URL.
- arXiv rate limit (≥3s global spacing) is non-negotiable; all arXiv calls go through the shared rate limiter — never add a bypass "just for one call".
- No secrets in the repo: signing via CI secrets; `local.properties` untracked.

## Definition of done (any task)

Code + tests written → spec honored (or spec updated deliberately) → `./gradlew build` green → ROADMAP updated → committed & pushed. For UI tasks additionally: light/dark previews exist, TalkBack labels on actionables, empty/loading/error states handled. If the change has a device-observable surface that CI can't exercise, its check is recorded in `VERIFICATION.md` (not silently assumed).

## Git

- Branch: one branch per subphase/PR, cut from latest `main`; never push to `main` directly. Open a ready-for-review PR per subphase and **self-merge once CI is green** (loop step 8); never force-push shared branches.
- Commits: imperative subject, scope prefix when natural (`database:`, `search:`, `ci:`), body explains *why* when non-obvious.
