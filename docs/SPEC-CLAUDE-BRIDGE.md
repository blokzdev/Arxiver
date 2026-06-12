# SPEC-CLAUDE-BRIDGE — Claude Routines Integration

**Status:** Approved · Implemented in `:core:claude`

## 1. Concept

The Claude app's **Routines** feature lets a user define an agentic routine (instructions + repo + connectors + permissions) with an **API trigger**: the routine runs when an authenticated POST hits its trigger URL. Arxiver is a *client* of that trigger: the user creates a routine in the Claude app (e.g. "Arxiver Digest" with instructions like *"You will receive JSON describing arXiv papers and an instruction; produce the requested digest, save it to my Drive, and email me a summary"*), then pastes the trigger URL + token into Arxiver.

Arxiver's job ends at delivering a **well-formed, information-rich payload**. The routine's instructions, connectors, model, and output destination are entirely the user's. This keeps the app free of LLM costs and lets users leverage every connector they have (Gmail, Drive, Notion-likes, etc.).

**v1 non-goal:** consuming routine *output* in Arxiver. Results land wherever the user's routine puts them (Claude app, email, Drive…). A result-webhook inbox is the top v2 candidate.

## 2. Routine configuration (in-app)

- Preferred path: the **guided setup wizard** (SPEC-ROUTINES-CATALOG §4) — template → create on claude.ai → paste URL + token → opt-in verification (§8). The direct Add dialog remains for experts.
- Add routine: **name** (user label), **trigger URL**, **token**. Optional: default action association.
- Validation on save: URL must be HTTPS; a **test ping** is offered but optional. The fire API has no dry-run, so a test always starts a real run — the UI shows a confirmation dialog saying so, and the ping turn itself instructs Claude to skip the routine's instructions, acknowledge, and stop (§3.1).
- Token storage: `EncryptedSharedPreferences` (Keystore master key). DB row stores `token_alias` only. Tokens are write-only in the UI (re-enter to change), excluded from backups/exports, never logged. If decryption fails (restore to new device), the routine shows "re-authentication needed".

## 3. Transport

> **Verified against the live endpoint (task 4.8, 2026-06-12).** The fire API wraps a routine session turn; the arxiver/v1 payload travels as the `text` field and the routine's instructions teach Claude to parse it.

```
POST https://api.anthropic.com/v1/claude_code/routines/{trigger_id}/fire
Authorization: Bearer <token>
anthropic-version: 2023-06-01
anthropic-beta: experimental-cc-routine-2026-04-01
Content-Type: application/json
User-Agent: Arxiver/<version>

{"text": "<arxiver/v1 payload JSON, ≤ 256 KB>"}
```

- URL normalization: users may paste the trigger URL with or without the `/fire` suffix; `RoutineTriggerClient.normalizeTriggerUrl` appends it for routine-shaped paths.
- Success = any 2xx. 401/403 → "token invalid/revoked" state on the routine config. 4xx other → failed, no retry. 5xx/network → exponential backoff retry ×3, then queued for `DispatchWorker` (network-constrained).
- Offline: dispatch is queued with status `queued` and sent when connectivity returns; user sees queue state in history.
- The transport remains isolated in `RoutineTriggerClient` — if the beta header or wrapper evolves, it stays one class to adapt.

### 3.1 Dispatch envelope (field-verified revision, 2026-06-12)

Live testing showed two failure modes of sending bare payload JSON as the turn: (1) routines with their own instructions don't recognize the blob as research content, and (2) test pings start a real run (the fire API has no dry-run) and the routine just executes its configured instructions. The turn text is therefore rendered by `DispatchEnvelope` and is **self-describing**:

- **Research dispatches** open with `ARXIVER RESEARCH DISPATCH (schema arxiver/v1)`, state the action, the user's instruction, and a short paper list, then embed the canonical arxiver/v1 JSON in a ` ```json ` fence with a note on how to use it (incl. fetching `pdf_url` for full text). Works with zero routine setup; starter instructions remain a quality upgrade, not a requirement.
- **Pings** open with `ARXIVER CONNECTIVITY TEST` and contain only a stand-down directive: skip the routine's normal instructions, acknowledge in one line, end the run. A ping still consumes a run — the UI confirms before sending and says so.
- The dispatch sheet preview renders the full envelope — exactly the text that leaves the device.

## 4. Payload schema (v1)

Versioned envelope; `schema` bumps on breaking change. Routine authors can rely on this shape — it's documented in-app ("copy routine starter instructions" button gives the user a paste-ready instruction block for their routine that explains the schema to Claude).

```json
{
  "schema": "arxiver/v1",
  "app_version": "1.0.0",
  "action": "digest",
  "sent_at": "2026-06-11T18:30:00Z",
  "instruction": "Digest these papers for a practitioner; focus on methods.",
  "context": {
    "include_notes": true,
    "library_size": 412,
    "user_tags_in_selection": ["ssm", "efficiency"]
  },
  "relations": {
    "similarity": [
      {"a": "2403.01234", "b": "2405.06789", "cosine": 0.831}
    ],
    "citations": [
      {"citing": "2405.06789", "cited": "2403.01234"}
    ],
    "library_neighbors": [
      {
        "arxiv_id": "2402.00007",
        "near": "2403.01234",
        "title": "…",
        "cosine": 0.792,
        "in_library": true,
        "abs_url": "https://arxiv.org/abs/2402.00007v1"
      }
    ]
  },
  "papers": [
    {
      "arxiv_id": "2403.01234",
      "version": 2,
      "title": "…",
      "authors": ["A. Researcher", "B. Scholar"],
      "abstract": "…",
      "primary_category": "cs.LG",
      "categories": ["cs.LG", "stat.ML"],
      "published": "2024-03-02",
      "updated": "2024-03-15",
      "doi": null,
      "abs_url": "https://arxiv.org/abs/2403.01234v2",
      "pdf_url": "https://arxiv.org/pdf/2403.01234v2",
      "citation_count": 87,
      "user": {
        "tags": ["ssm"],
        "status": "read",
        "rating": 4,
        "notes": ["My note text…"]
      }
    }
  ]
}
```

- `papers[].user` present only when `include_notes` is toggled on for the dispatch (per-dispatch toggle, defaulting to the routine config's setting). Privacy: the confirm sheet always previews exactly what leaves the device.
- `relations` (optional, additive — absent when there is nothing to report) ships the device's precomputed analysis primitives so the routine can *compose* relationships instead of re-deriving them from raw text (interface design informed by SpatialClaw, arXiv 2606.13673: agents reason better over composable perception primitives than over flat inputs behind a rigid interface):
  - `similarity`: pairwise embedding cosine between selected papers (only pairs where both embeddings exist; rounded to 3 decimals).
  - `citations`: citation edges whose *both* endpoints are in the selection.
  - `library_neighbors`: each selected paper's top-3 precomputed semantic neighbors from the local corpus (`near` names the anchor). These reveal what's on the user's device, so they ride the `include_notes` privacy gate — with notes off the key is structurally absent.
- Abstract always included (Claude shouldn't need to re-fetch); PDFs never uploaded — links suffice, routines can fetch.
- Size guard: > 256 KB (≈ >40 papers with notes) → app refuses with "split the selection" message.

## 5. Action catalog

| action | selection | instruction template (user-editable before send) |
|---|---|---|
| `digest` | 1–N papers | "Produce a structured digest of each paper: TL;DR, key contributions, methods, limitations." |
| `deep_dive` | 1 paper | "Read the full PDF and produce a deep technical analysis: approach, evidence quality, reproducibility, open questions." |
| `compare` | 2–6 papers | "Compare these papers: shared problem, differing approaches, trade-offs, which to build on and why." |
| `weekly_review` | auto: library adds + top inbox of last 7 days | "Synthesize my week in research: themes, must-reads, what I should queue next." |
| `literature_scan` | 0–N papers + required free-text question | "Investigate this question. Local context attached; extend with your own search." |
| `custom` | any | empty — user writes the instruction |
| `ping` | none | connectivity test payload, `papers: []` |

Every dispatch flows through the same confirm sheet: routine picker → editable instruction → notes toggle → payload preview (collapsible JSON) → send.

## 6. Dispatch history

List of `routine_dispatches`: action, routine name, paper count, status chip (queued/sent/failed + HTTP code), timestamp. Tap → payload preview + retry (failed/queued) + delete. Retention 90 days.

## 7. Testing

- `PayloadBuilder`: golden-file JSON tests per action (kotlinx.serialization output vs committed fixtures).
- `RoutineTriggerClient`: MockWebServer — 200/401/500/timeout/offline-queue paths.
- Size guard and notes-toggle redaction unit-tested (a payload with `include_notes=false` must contain no `user` keys anywhere — asserted structurally; likewise no `library_neighbors` key in `relations`).
- End-to-end against a real routine trigger: manual checklist item before Phase 4 sign-off (requires user-provided routine; see ROADMAP).
- Guided setup (§8): `VerificationError` mapper covers every `DispatchSubmission` shape; wizard ViewModel tests cover step gating, save-before-verify ordering, and one case per error class.

## 8. Guided setup & verification

The wizard (SPEC-ROUTINES-CATALOG §4) ends with a verification step. Its contract:

### 8.1 Test-dispatch semantics

- Verification **is** the stand-down ping of §3.1 — there is no separate mechanism and the fire API has no dry-run, so **a test consumes a real run**.
- Therefore verification is **opt-in, never automatic**: the verify step offers *Send test ping* and *Skip for now* as equal citizens, and states the run cost in the consent copy.
- **Save before verify:** the routine config is persisted (token in `TokenVault`) when the user completes the connect step, before any ping. A failed or skipped verification never loses input; the routine simply exists unverified, like one added via the expert dialog.
- Success = any 2xx on the fire call. The wizard shows a verified state and reminds the user the run in the Claude app should contain a one-line acknowledgement.

### 8.2 Error taxonomy & troubleshooting

Ping outcomes map to a typed `VerificationError`; each class gets cause + actionable fix in the UI (*Retry test* / *Edit URL & token* / *Keep routine anyway*):

| Signal | Class | Likely cause | Actionable fix |
|---|---|---|---|
| 401 / 403 | `BadToken` | token mistyped, revoked, or from another routine | re-copy the token from the routine's API-trigger settings; routine is flagged `authInvalid` |
| 404 | `WrongUrl` | trigger URL wrong or truncated | URL should look like `https://api.anthropic.com/v1/claude_code/routines/…` — `/fire` is appended automatically |
| 400 | `BadRequest` | a claude.ai *page* URL pasted instead of the trigger URL; or contract drift | re-copy the trigger URL from the routine's API-trigger settings |
| other 4xx | `Rejected(code)` | permanent rejection | show code; check the routine still exists and the trigger is enabled |
| 5xx | `ServerError(code)` | Claude-side trouble | ping stays queued and auto-sends (`DispatchWorker`); retry later |
| transport/offline | `Offline` | no connectivity | ping queued, auto-sends when back online — safe to finish setup |
| vault miss | `TokenUnavailable` | token undecryptable (e.g. restored backup) | re-enter the token |
