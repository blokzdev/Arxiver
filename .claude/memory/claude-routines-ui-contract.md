---
name: claude-routines-ui-contract
description: Observed claude.ai/code/routines creation UI — fields, trigger types, connector permission model
type: reference
---

Observed 2026-06-11 in the Claude app (mobile web), claude.ai/code/routines → New routine:

- Fields: Name (required), Instructions ("describe what Claude should do in each session"), model picker (e.g. Opus 4.8, 1M context), optional GitHub repo attachment.
- Triggers (choose one): **Schedule** (recurring cron or once at a future time), **GitHub event** (webhook), **API** ("trigger from your own code by sending a POST request").
- Per-routine **Connectors** list, with an explicit warning that Claude can use ALL tools from attached connectors — including writes — without permission prompts during runs. Catalog templates must therefore specify a minimal connector set.
- Further tabs exist: Behavior, Permissions (contents not yet captured).
- The API trigger contract is field-verified as of 2026-06-12 (ROADMAP 4.8 closed): POST `…/routines/{trigger_id}/fire` with Bearer token + `anthropic-version` + `anthropic-beta` headers, body `{"text": "<turn>"}` — canonical in docs/SPEC-CLAUDE-BRIDGE.md §3. `core/claude/src/main/kotlin/dev/blokz/arxiver/core/claude/RoutineTriggerClient.kt` remains the deliberate single point of change if it drifts.
