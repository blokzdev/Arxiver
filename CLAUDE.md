# CLAUDE.md — Arxiver Engineering & Workflow Guide

Guidance for Claude Code sessions working in this repo. The project is built documentation-first and roadmap-driven; this file defines the self-governing loop.

## What this project is

Local-first Android arXiv explorer (Kotlin + Jetpack Compose) with on-device hybrid search and a Claude Routines dispatch bridge. Read in order if new to the repo: `docs/PRD.md` → `docs/ARCHITECTURE.md` → the SPEC for whatever you're touching → `ROADMAP.md`.

## The loop (checkpoint protocol)

Every working session, and repeatedly within a session:

1. **Orient:** read `ROADMAP.md`; find the first `[~]` or `[ ]` task. Never skip ahead across an unmet CHECKPOINT.
2. **Mark:** set the task `[~]` (in progress).
3. **Read the spec** section governing the task before writing code. Specs are the source of truth; if implementation must deviate, update the spec in the same commit and note it in the Decision log (ROADMAP bottom).
4. **Implement** with tests per the spec's testing section.
5. **Verify:** `./gradlew build` (compiles + unit tests + lint) must pass. Never commit red.
6. **Record:** mark task `[x]`, update Decision log if decisions were made.
7. **Commit & push:** one commit per task (or tight task cluster), descriptive message, push to the designated branch (`git push -u origin <branch>`; on network failure retry ×4 with 2/4/8/16s backoff).
8. **Checkpoint gates:** at a phase CHECKPOINT, run the full phase verification listed in ROADMAP, fix anything failing, then check the checkpoint box in its own commit titled `checkpoint: phase N`.
9. Tasks tagged `[needs-user]` (e.g. real routine token): ask the user, mark `[!] blocked` with reason if no answer, and continue with the next non-dependent task.

Rules: no `[x]` without green build · ROADMAP edits travel in the same commit as the work · blocked ≠ stopped (find the next unblocked task) · when context is summarized mid-session, re-orient from step 1.

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

Code + tests written → spec honored (or spec updated deliberately) → `./gradlew build` green → ROADMAP updated → committed & pushed. For UI tasks additionally: light/dark previews exist, TalkBack labels on actionables, empty/loading/error states handled.

## Git

- Branch: work happens on the session's designated branch; never push elsewhere without explicit permission. No PR creation unless the user asks.
- Commits: imperative subject, scope prefix when natural (`database:`, `search:`, `ci:`), body explains *why* when non-obvious.
