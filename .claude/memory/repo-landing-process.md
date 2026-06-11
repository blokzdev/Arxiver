---
name: repo-landing-process
description: How changes land — protected main, PR-only, CI runs on pull_request (not branch pushes)
type: process
---

- `main` is protected: direct pushes are rejected; everything lands via PR with the required "Build, test, lint" check.
- Since PR #3 (2026-06-11), CI `push` runs fire on `main` only. Pushing a working branch does NOT run CI — the required check comes from the `pull_request` event (which builds the synthetic merge with main).
- **How to apply:** open a PR (draft is fine) early on any working branch to get cloud CI; `./gradlew build` locally remains the pre-commit gate. The post-merge `push` run on main is the only run permitted to write the Gradle cache.

Related: [[release-engineering-state]]
