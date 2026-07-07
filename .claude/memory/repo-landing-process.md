---
name: repo-landing-process
description: How changes land — protected main, PR-only, CI runs on pull_request (not branch pushes)
type: process
---

- `main` is protected: direct pushes are rejected; everything lands via PR with the required "Build, test, lint" check.
- Since PR #3 (2026-06-11), CI `push` runs fire on `main` only. Pushing a working branch does NOT run CI — the required check comes from the `pull_request` event (which builds the synthetic merge with main).
- **How to apply:** open a PR (draft is fine) early on any working branch to get cloud CI; `./gradlew build` locally remains the pre-commit gate. The post-merge `push` run on main is the only run permitted to write the Gradle cache.
- **Merge-timing gotcha (seen P4.2, 2026-07-06):** `gh pr checks <n> --watch` can exit **0 before the required check actually passes** — a second "Build, test, lint" run sometimes re-triggers after the first, and `--watch` returns on the first-seen completion. A `gh pr merge` right after can be refused ("requirements not met") even though the watch said green. **Always confirm `gh pr view <n> --json mergeStateStatus` is `CLEAN` (and `gh pr checks` shows `pass`, not `pending`) before merging.** Don't reach for `--admin`/`--auto` to paper over it — just re-watch the pending run.

Related: [[release-engineering-state]]
