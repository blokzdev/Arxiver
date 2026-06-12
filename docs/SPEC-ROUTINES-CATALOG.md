# SPEC-ROUTINES-CATALOG — Routine Template Catalog & Guided Setup

**Status:** Approved (user-reviewed 2026-06-12 via Phase 6 plan) · Catalog version: **1**

> **Canonical source note:** the template *content* below (names, purposes, instruction preambles, connector sets) is canonical in code at `core/claude/src/main/kotlin/dev/blokz/arxiver/core/claude/RoutineTemplates.kt` (`RoutineTemplateCatalog`). This spec describes the catalog and its contract; golden tests pin the code to the invariants in §6. If the two drift, fix the code or update this spec in the same commit.

## 1. Purpose & principles

The Claude bridge (SPEC-CLAUDE-BRIDGE) is deliberately unopinionated: any routine with the trigger URL pasted into Arxiver works. The catalog adds the missing on-ramp — **curated, paper-centric routine templates** plus a guided setup wizard, so a non-expert goes from "this template looks useful" to a verified working routine without understanding the transport.

Principles:

- **Minimal connector sets.** Connectors attached to a routine run **without permission prompts** during runs (observed claude.ai routine-creation UI). Every template therefore defaults to **no connectors** — results land in the routine's run log in the Claude app — and names optional delivery upgrades (email, Drive) the user adds deliberately, understanding the trade.
- **Self-sufficient instructions.** Each template's paste-ready instruction text = a template-specific preamble (role, output shape) + the **shared dispatch-recognition core** (how to parse `ARXIVER RESEARCH DISPATCH`, how to stand down on `ARXIVER CONNECTIVITY TEST`). A template-created routine needs nothing else pasted.
- **Templates are reference data, not user data.** Bundled as a versioned Kotlin object; no database rows, no syncing, no template↔routine link persisted. The wizard prefills the routine name from the template and otherwise produces an ordinary routine config.

## 2. Template anatomy

| Field | Meaning |
|---|---|
| `id` | stable kebab-ish key, e.g. `paper_digest` — never reused across versions |
| `name` | display name, doubles as the prefilled routine name |
| `purpose` | 1–2 sentences for the catalog UI: what you get, when to use it |
| `action` | the `RoutineAction` Arxiver should preselect when dispatching to a routine built from this template |
| `instructionPreamble` | template-specific routine behavior; combined with the shared recognition core by `RoutineStarterInstructions.generateFor` |
| `triggerGuidance` | setup steps on the claude.ai side (always: API trigger) |
| `connectors` | minimal connector set (usually empty) — the catalog UI explains the run-log default and optional upgrades |

## 3. The templates (catalog v1)

### 3.1 Paper Digest — `paper_digest` → `digest`

**Purpose:** structured digest of each dispatched paper. The entry-level template.
**Connectors:** none (run log) → optional: email or Drive for delivery.

> You are my paper-digest assistant. When a research dispatch arrives, produce a structured digest of EACH paper: TL;DR (2–3 sentences), key contributions, methods, limitations, and why it matters to me (use my tags and notes when present). Practitioner's density — no filler. Unless this routine has a delivery connector attached, write the digests directly in this run's output.

### 3.2 Deep-Dive Analyst — `deep_dive_analyst` → `deep_dive`

**Purpose:** full-text technical analysis of a single paper, from the PDF.
**Connectors:** none (web fetch is built-in) → optional: Drive for a saved report.

> You are my deep-dive analyst. For each dispatched paper, fetch the PDF from its pdf_url and read the full text — never work from the abstract alone. Produce a deep technical analysis: problem setup, the approach and its key technical ideas, evidence quality (experiments, baselines, ablations), reproducibility signals (code and data availability), limitations and open questions, and how it relates to any other papers or neighbors in the payload. Dispatches here usually carry one paper; give it full attention.

### 3.3 Paper Comparator — `paper_comparator` → `compare`

**Purpose:** head-to-head comparison of 2–6 papers, composing the device-computed `relations` block.
**Connectors:** none.

> You are my paper comparator. Build a comparison of the dispatched papers: shared problem framing, differing approaches, head-to-head trade-offs (a table works well), and a verdict — which to build on and why. The payload's "relations" block carries embedding similarity and citation edges computed on my device: compose those signals (cluster near-duplicates, note who cites whom) instead of re-deriving them from the text.

### 3.4 Weekly Research Review — `weekly_review` → `weekly_review`

**Purpose:** Sunday-morning synthesis of a week of saves and top inbox papers.
**Connectors:** none → optional: email so the review lands in the inbox.

> You are my weekly research reviewer. Each dispatch is a week of my research activity: library saves and top inbox papers. Synthesize the week: the 2–4 themes that emerged, the must-reads and why, what looks skippable, and what I should queue next week. Use my tags, ratings, and notes to read my interests; the "relations" block shows the clusters. Make it scannable — this is a Sunday-morning read.

### 3.5 Literature Scout — `literature_scout` → `literature_scan`

**Purpose:** investigate a research question, extending seed papers with the routine's own searching.
**Connectors:** none (uses built-in web search where available).

> You are my literature scout. Each dispatch carries a research question in MY INSTRUCTION plus optional seed papers as local context. Investigate the question: extend beyond the seeds with your own searching, then return an annotated reading list — for each recommendation give the citation, a one-paragraph relevance note, and how it relates to the seeds. Distinguish clearly between papers I sent and papers you found.

### 3.6 Reproducibility Auditor — `repro_auditor` → `custom`

**Purpose:** can you actually run this paper? Code/dataset sleuthing + repo health + a reproducibility score.
**Connectors:** GitHub (read) recommended for direct repo inspection; degrades gracefully without it.

> You are my reproducibility auditor. For each dispatched paper: find its code and datasets (paper links, GitHub search), check repo health (recent commits, open issues, license, a plausible install path), verify that claimed artifacts actually exist, then score reproducibility high/medium/low with reasons. Be skeptical — "code available" claims often aren't. If this routine has GitHub access, inspect the repositories directly.

### 3.7 Reading-Queue Prioritizer — `queue_prioritizer` → `custom`

**Purpose:** turns a pile of dispatched papers into an ordered reading plan.
**Connectors:** none.

> You are my reading-queue prioritizer. Rank the dispatched papers in the order I should read them, with a short justification each: expected value to me (lean on my tags, notes, and ratings when present), prerequisite ordering (read X before Y), and an effort estimate (skim / read / study). The "relations" block reveals clusters and what sits near my existing library — use it. End with a concrete plan: today, this week, and what to drop.

### 3.8 Flashcard & Notes Generator — `flashcard_generator` → `custom`

**Purpose:** retention material — spaced-repetition flashcards plus structured notes per paper.
**Connectors:** none → optional: Drive to collect the notes.

> You are my retention assistant. Distill each dispatched paper into study material: first 5–10 spaced-repetition flashcards (question on one line, answer on the next) covering the core idea, method, key numbers, and limitations; then half a page of structured reading notes I can revisit. Write for future-me who has forgotten the paper — fully self-contained, no "as mentioned above".

## 4. Guided setup wizard

Entry points: template detail screen ("Set up this routine"), Claude routines screen (empty state prefers the wizard; the expert Add dialog remains), Settings → Claude. Route carries an optional `templateId`; without one the wizard runs in generic mode (no template recap, generic starter instructions).

Three steps (single screen, internal stepper):

1. **Create on claude.ai** — template recap + numbered checklist: new routine at claude.ai/code/routines → paste the copied instructions → choose the **API trigger** → attach only the template's minimal connectors. Buttons: *Copy instructions* (`RoutineStarterInstructions.generateFor`), *Open claude.ai* (browser intent).
2. **Connect to Arxiver** — name (prefilled from template), trigger URL, token. Live validation: HTTPS required (hard), missing `/routines/` path segment (soft warning — matches `normalizeTriggerUrl`'s shape), token non-blank, password-masked. The token flows only through the existing add-routine path into `TokenVault` (red lines apply).
3. **Verify** — opt-in test ping per SPEC-CLAUDE-BRIDGE §8. The routine is saved on entering this step, *before* any ping, so verification failure never loses input.

## 5. Versioning

`RoutineTemplateCatalog.CATALOG_VERSION` (int, starts at 1) bumps whenever template content changes meaningfully (instruction semantics, connector sets, additions/removals). Template `id`s are never reused. The catalog UI may show the version; nothing persists it.

## 6. Testing

- Catalog invariants (unit, `:core:claude`): exactly 8 templates; unique non-blank ids/names; action coverage — `digest`, `deep_dive`, `compare`, `weekly_review`, `literature_scan` each exactly once, `custom` three times, `ping` never; all text fields non-blank; no secret-shaped content (no `Bearer `, no `sk-`).
- Instruction generation: every `generateFor(template)` output contains the template preamble, both recognition markers (`ARXIVER RESEARCH DISPATCH`, `ARXIVER CONNECTIVITY TEST`) and stand-down language; the recognition core is verbatim-identical between `generate()` and every `generateFor(...)` (no divergence).
- Wizard behavior: see SPEC-CLAUDE-BRIDGE §8 (verification contract) — ViewModel tests cover step gating, save-before-verify ordering, and every error class.
