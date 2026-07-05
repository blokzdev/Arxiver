# SPEC-P-TOOLS — agentic in-chat tool-use (Phase P-Tools)

Status: **active** · Owner: chat / retrieval surface · Related: `SPEC-AI-PROVIDERS.md` (the `AiProvider`/`ChatChunk` contract + the "what leaves the device" confirm this extends), `SPEC-DATA.md` (chat-history storage + migrations + the backup wall), `SPEC-SEARCH.md` (the on-device hybrid path `search_my_library` reuses), `SPEC-P-HTML.md` (the `AllowedHosts` + dedicated `@ArxivClient` rate-limited client all external tools route through), `SPEC-UI.md` (the chat surface + the inline activity log). Planned via one Ultracode pass (4 mappers → 3 design stances → a 3-lens adversarial judge panel [architecture · privacy-redline(veto) · scope-sequencing] → synthesis) **+ a personal adversarial validation** that verified every load-bearing claim at file:line on the merged post-P-Chat code (see the ROADMAP P-Tools section and the 2026-07-04 decision-log row).

## 1. Purpose & scope

Today retrieval is **RAG-before-the-turn** over what the user already holds: `ChatRepository.prepare` embeds the question, retrieves chunks, folds them into the `ChatRequest`, and streams one reply. P-Tools gives the chat model a **local tool loop** — the Claude-connector model — so mid-conversation it can search the *living* research corpus, reason over the results with the user's BYOK provider, and act on them:

- `search_my_library` — the user's own indexed library (**zero egress**, on-device hybrid search)
- `search_arxiv` / `get_paper` / `import_to_library` — arXiv discovery + one-tap import into the P-HTML reader / PDF pipeline
- `search_semantic_scholar` — S2 (which indexes arXiv + bioRxiv + medRxiv + PubMed + venues), full search + details/batch + recommendations
- `search_chemrxiv` — Cambridge Open Engage (chemRxiv)

The multi-turn loop lives in a **repo-driven orchestrator ABOVE the providers**; the providers stay single-shot and gain three additive, default-off fields plus a `supportsTools` capability flag. On-device **degrades cleanly to no-tools**. Trust is the real design work: local search needs no consent; external tools require a **per-conversation** "Enable web search for this chat" toggle, and every tool call is disclosed **twice** — the confirm lists the tool *definitions* that leave the device, and a **persistent inline activity log** shows each model-minted query, its source, and whether it egressed.

**In scope (PT.0–PT.4):** the tool-loop plumbing + consent/disclosure model with a fake local tool (PT.0); `search_my_library` (PT.1); `search_arxiv`+`get_paper`+`import_to_library` (PT.2); full `search_semantic_scholar` + optional S2 BYOK key (PT.3); `search_chemrxiv` on a new host (PT.4).

**Explicitly OUT of this phase** (tracked in §14, routed to backlog / `HUMAN.md`): on-device tool-use (LiteRT-LM tool support is an experimental flag); S2 author-search & snippet-search; generic (non-arXiv-keyed) import; a chunk-level `RetrievalScope.Library` tool (PT.1 satisfies the "chat my whole library" intent at **paper** granularity); live tool-arg streaming; concurrent tool execution. **Not** first-class multi-source ingestion — that is the later, demand-gated **P-Sources** phase; P-Tools ships cross-disciplinary discovery as *tools*, not ingestion (≈10% of the ingestion cost for most of the discovery value).

## 2. Architecture — the loop lives ABOVE the provider

**Decision.** The multi-turn tool loop is a **`ChatToolLoop` collaborator invoked from `ChatRepository.stream` (`:app`)**, not inside the providers. Each `provider.chat(request)` remains exactly one HTTP round-trip = one `Flow<ChatChunk>`.

**Why not loop-inside-the-provider (rejected Stance B).** Placing the loop in `:core:ai` was argued honestly and is the most API-faithful shape, but it loses on the two axes that matter for this codebase:
1. **Persistence + finalize.** `ChatRepository.stream` (the single-turn `NonCancellable` finalize at `ChatRepository.kt:276–282`) is the *only* place that can reconstruct an interrupted multi-turn turn. A provider-owned loop cannot rebuild the in-flight message list from chunks after process death → a cancelled/failed agentic turn would half-persist or strand a dangling `tool_use`.
2. **Disclosure surface.** The "what leaves the device" confirm + the inline activity log live in `:app`. A provider-owned loop splits the disclosure surface across the module boundary.
3. **Duplication.** The loop / cap / dangling-guard / budget logic would be duplicated across `AnthropicProvider` + `GeminiProvider`.

We **keep** everything else Stance B got right (the SSE-parsing detail, the migration analysis, the consent model, the PT.0–PT.4 decomposition) and only flip the loop's location. The loop-above stance was ranked highest by all three judges.

**Module boundaries** (unchanged invariant `:core:* ∌ :app`): `:core:ai` owns the neutral tool *contract* + per-provider wire mapping; `:app` owns the loop, the `ToolRegistry`, the executors (which call `:app`/`:core` repositories), consent, persistence, and disclosure.

## 3. `:core:ai` interface contract (additive, default-off)

`fun chat(request: ChatRequest): Flow<ChatChunk>` — **signature UNCHANGED.** Everything is additive with defaults, so every existing caller (`ChatRepository.stream`, the pinned-tier `OnDeviceProvider.chat(request, pinTier)` test path, `insertArtifactTurn`) and **both redaction goldens** (`ChatPreviewBuilderTest`, `core/claude` `PayloadBuilderTest`) stay byte-identical until a tool actually attaches.

1. **`ChatRequest.tools: List<ToolDef> = emptyList()`** — `ToolDef { name: String, description: String, inputSchema: JsonObject }`. Default empty ⇒ byte-identical wire + goldens.
2. **`ChatChunk.ToolUse(id: String, name: String, inputJson: String)`** — a new sealed-interface case beside `Delta`/`Done`. It **carries BOTH `id` AND `name`**: Anthropic correlates `tool_result` by the opaque `tool_use.id`; Gemini correlates `functionResponse` purely by function `name` (and can call the same function twice in parallel). The neutral chunk carries both so each provider's resume-turn builder maps its own. **Buffered-to-complete** — emitted at `content_block_stop` (Anthropic) / on the whole `functionCall` (Gemini), never streamed partial (no MVP UI wants live tool-arg streaming; §14 defers it).
3. **Two tool-block carriers on `ChatMessage`** (so the orchestrator can build the resume request):
   - **`ChatMessage.toolCalls: List<ToolCall> = emptyList()`** on an **assistant** turn — the `tool_use` blocks the model emitted. `ToolCall { id, name, inputJson }` (shares `ChatChunk.ToolUse`'s shape). **This is load-bearing and easy to miss:** Anthropic returns HTTP 400 if a `tool_result` lacks a matching `tool_use` in the *immediately preceding* assistant turn, so the resume request must replay the assistant's `tool_use` turn, not just the results.
   - **`ChatMessage.toolResults: List<ToolResult> = emptyList()`** on a **`ChatRole.TOOL`** turn — `ToolResult { callId: String, name: String, contentJson: String, isError: Boolean }`.
4. **`ChatRole.TOOL`** — a new role for the executed-results turn. Wire mapping: Anthropic → a `user` message whose content is `tool_result` blocks; Gemini → a `user` (function-response) content. The existing `ChatRole.wire()` maps non-assistant → `"user"` in both providers, so `TOOL` slots in without breaking the text path.
5. **`ProviderCapability.supportsTools: Boolean = false`** — mirrors how `vision` was added. `true` on `AnthropicProvider` + `GeminiProvider`, `false` on `OnDeviceProvider`. The orchestrator gates tool attachment **strictly** on this flag (mirroring `ChatRepository.resolveVisionCapable`) — never attach `request.tools` to a `supportsTools == false` provider, or the on-device engine silently ignores them and the loop never terminates on a `ToolUse`.

**Text-only invariant (enforced):** when `tools`/`toolCalls`/`toolResults` are all empty, the wire bytes and both redaction goldens are byte-identical to pre-P-Tools. `ChatPreviewBuilder` uses `explicitNulls = false`, so a nullable/empty `tools` disclosure omits rather than serializes.

## 4. Provider wire mappings

### 4.1 Anthropic (`AnthropicProvider`)
- **Request:** add `tools: [{ name, description, input_schema }]` to `WireRequest` (omitted when empty). `anthropicContent` (`AnthropicProvider.kt:144–171`) already builds content-block arrays for images; `tool_use` (assistant, from `toolCalls`) and `tool_result` (from `toolResults`) are two more block types:
  - assistant `tool_use` block: `{ type:"tool_use", id, name, input:<inputJson> }`
  - tool-result block (on a `user` message): `{ type:"tool_result", tool_use_id:<callId>, content:<contentJson>, is_error?:true }`
- **SSE parse (the crux edit at `AnthropicProvider.kt:89` — do NOT `continue`-drop):** accumulate a `tool_use` block across `content_block_start` (`type=="tool_use"` → capture `id` + `name`) + `content_block_delta` (`input_json_delta.partial_json` concatenated) + `content_block_stop` (seal → **emit `ChatChunk.ToolUse(id, name, inputJson)`**). `message_delta.stop_reason == "tool_use"` (already captured today) tells the orchestrator to resume. A malformed/partial tool block must fail loudly, never silently swallow (§12 — the silent-drop-hang guard).

### 4.2 Gemini (`GeminiProvider`)
- **Request:** add `tools: [{ functionDeclarations: [{ name, description, parameters:<inputSchema> }] }]` + `toolConfig.functionCallingConfig.mode = "AUTO"` to `GenRequest`. Add a `functionCall` field to `GenPart`.
- **Resume turns:** a `model`-role content carrying the `functionCall` part(s) (from `toolCalls`) + a `user`-role content carrying `functionResponse { name, response:<contentJson> }` (from `toolResults`). *(Gemini's exact function-response role/envelope is confirmed against a live call during PT.0 impl — REST docs are terse.)*
- **SSE parse:** emit `ChatChunk.ToolUse(id = <synthesized-or-name>, name, inputJson = args)` when a `candidate.content.parts[i]` carries a `functionCall{name,args}` (parser today reads only `parts[0].text` at `GeminiProvider.kt:82–87`). Gemini supplies no opaque id → synthesize a stable per-turn id; the resume maps by `name`.

## 5. The tool loop (orchestrator)

`ChatToolLoop`, invoked from `ChatRepository.stream`, holds the growing message list in the **repo coroutine scope** (so `finalize` sees it — §12) and runs:

1. Build `request` with `tools` — attached **only** when `capability.supportsTools` **and** (for external tools) the conversation's consent flag is on; `search_my_library` (zero-egress) is always attached.
2. `provider.chat(request).collect { }` — the existing single round-trip. Buffer any `ChatChunk.ToolUse`; stream `Delta`s to the UI as today.
3. On `Done`: if `ToolUse` chunks were buffered (stop_reason `tool_use`) → **execute ALL of them** via the `ToolRegistry` (§6), then append **ONE** assistant turn carrying **all** `tool_use` blocks (`toolCalls`) + **ONE** `TOOL` turn carrying **all** `ToolResult` blocks (`toolResults`). **Never N separate turns** — Anthropic 400s on a dangling `tool_use`. Re-invoke `chat()`.
4. Repeat until a text `Done` with no tool calls, **or** `iteration == MAX_TOOL_ITERATIONS` (**5**, ctor-injectable).
5. **Cap-hit semantics:** if the cap is hit with a pending `tool_use`, still **run the pending tools** (so no dangling `tool_use`), then send **one final round-trip with `tools` OMITTED** — forcing a text answer — and emit a terminal activity marker "stopped after N tool calls."
6. **Per-result context-budget truncation:** each tool result is truncated to a per-result char budget **before** it is appended. `ChatContextAssembler`'s char/4 fitting ran once at prepare, *before* any tool result existed; unbounded arXiv/S2 payloads would blow the window. (Anthropic's 200k is generous, but the budget still bounds cost + latency.)

**MVP executes tool calls serially** through the shared per-host limiters/mutexes (§6, §7); a bounded-concurrent executor is a §14 deferral.

## 6. `ToolRegistry` + `ToolExecutor` (`:app`)

A `ToolRegistry` owns the `ToolDef` catalog and a `name → handler` dispatch executor. It (a) builds the `List<ToolDef>` for a request, **filtered by consent + per-tool egress class**, and (b) executes a call through **existing infra — never a red-line bypass**:

| Tool | Egress | Backing path (existing infra) |
|---|---|---|
| `search_my_library` | **none** (local) | on-device hybrid: `LocalKeywordSearch` + `VectorIndex.topK` fused via `HybridFusion.fuse`; `EmbeddingService.embedQuery` (BGE prefix) |
| `search_arxiv` | arxiv | `PaperRepository.searchArxiv` → `ArxivApiClient` (≥3s `ArxivRateLimiter`, `@ArxivClient`) |
| `get_paper` | arxiv | `PaperRepository.paper(id)` (cache-first fetch+cache) |
| `import_to_library` | arxiv | `PaperRepository.paper(id)` **then** `LibraryRepository.save(id)` — persist the `papers` row **before** the library entry or it ghost-joins |
| `search_semantic_scholar` | s2 | `SemanticScholarClient` moved onto the `@ArxivClient` gated client (PT.3), ≥1.2s self-spacing mutex |
| `search_chemrxiv` | chemrxiv | new `@ArxivClient` gated client (PT.4), own polite mutex (**not** the 3s limiter) |

## 7. Tool catalog (per-tool contract)

Each tool declares a JSON input schema; results are returned as compact JSON the model reads and (for search tools) mirror the existing `[n] (arXiv:id)` citation contract.

- **`search_my_library`** (PT.1) — `{ query: string, k?: int }`. Paper-level hybrid search over the user's library; `k ≈ 5–8` (NOT the default `resultLimit=20`), abstract truncated. Result per hit: `{ paperId, title, abstractSnippet, score, provenance, inLibrary:true }`. Keyword-only **degrade** (embed model not `Ready`) returns keyword hits with a `degraded` flag. Honors the `includeNotes` gate — a library search must not leak notes into a cloud turn.
- **`search_arxiv`** (PT.2) — `{ query: string, category?: string, date_range?: {from,to}, max_results?: int }` → `ArxivQuery`/`SearchFilter`. Result: list of `{ arxivId, title, authors, abstractSnippet, primaryCategory, published }`.
- **`get_paper`** (PT.2) — `{ arxiv_id: string }` → full metadata (cache-first).
- **`import_to_library`** (PT.2) — `{ arxiv_id: string }` → `{ imported:bool, alreadyInLibrary:bool }`. Idempotent; lights up the P-HTML reader / PDF by id (no new plumbing).
- **`search_semantic_scholar`** (PT.3) — `{ query: string, limit?: int, venue?: string, year?: {from,to} }` → S2 `/graph/v1/paper/search`; per hit `{ title, abstract?, tldr?, year, venue, authors, citationCount, externalIds{ArXiv?,DOI?,PubMed?}, openAccessPdf? }`. `import_to_library` accepted **only** when `externalIds.ArXiv` is present (the arXiv-keyed schema — §10, §14). Details/batch/recommendations as sibling calls.
- **`search_chemrxiv`** (PT.4) — `{ term: string, limit?: int, skip?: int }` → Cambridge Open Engage `/engage/chemrxiv/public-api/v1/items`; per hit `{ title, abstract, doi, authors, publishedDate, pdfUrl? }`. Exact envelope confirmed against a live GET during PT.4 impl.

## 8. Persistence — `tool_invocations` table (`MIGRATION_4_5`)

Tool calls are **assistant-authored intermediate steps of ONE logical turn**, persisted in a **new `tool_invocations` table FK'd to the single assistant `chat_messages.id`** — **not** new `chat_messages` roles. Verified rationale: new roles would silently corrupt (a) `ChatDao.observeSessionRows`' snippet subquery (`chat_messages … content != ''`, `ChatDao.kt:102`), (b) `messagesFor`'s COMPLETE-turn history re-feed (`ChatRepository.kt:137`), (c) the hydrate ghost-filter + `toAskMessage`. The separate table keeps tool rows out of all of these **automatically**.

**Columns:** `message_id` (FK, assistant message), `tool_name`, `query`, `result_summary`, `egress` (bool), `ordinal`, `created_at`.

**Consent column:** `chat_sessions.tools_enabled INTEGER NOT NULL DEFAULT 0` (same migration) — survives process death / hydrate, giving "per-conversation" its literal meaning.

**Migration discipline** (CLAUDE.md / SPEC-DATA): DB `VERSION 4 → 5` (verified currently 4 at `ArxiverDatabase.kt:103`); register `MIGRATION_4_5` in `addMigrations(...)`; commit the **KSP-generated `5.json`** under `core/database/schemas/`; a Robolectric `runMigrationsAndValidate` identity-hash test + the `MigrationHarnessTest` auto-coverage; `@ColumnInfo(defaultValue="0")` on `tools_enabled` must **byte-match** the `ALTER … DEFAULT 0` literal (the PC.4 identity-hash lesson). **Destructive migration forbidden.**

**Ephemeral-for-context rule:** `prepare()` does **NOT** replay persisted `tool_invocations` into a later turn's context — only COMPLETE user/assistant turns re-feed. The rows exist for the **activity-log render + audit**, not re-grounding.

## 9. Consent & disclosure model

**Consent (per-conversation, not per-call):** `chat_sessions.tools_enabled` gates whether the registry hands **EXTERNAL** tool defs to the provider. A toggle in the `AskSheet` chrome ("Enable web search for this chat"), wired like `onSetIncludeNotes`. `search_my_library` is zero-egress and needs **no** consent — always available. The existing `isCloud` "what leaves the device" confirm stays.

**What leaves the device on a tool call:** (a) the tool **definitions** (names/descriptions/schemas) on the first request; (b) each model-minted tool **query** string and each re-fed tool **result** turn on every resume round-trip. A tool loop makes **N** cloud round-trips, not one. Tool results are app-fetched **public** arXiv/S2/chemRxiv data (not chat content) — they do not touch the backup red-line (§10) but they **do** leave the device and MUST be surfaced.

**Confirm disclosure (hard PT.0 gate):** `ChatPreviewBuilder`/`PreviewDto` (`ChatPreviewBuilder.kt`) today serialize only `{system, messages, maxTokens}` — verified, they never see `request.tools`. Before ANY tool def attaches, extend `PreviewDto` + `ChatPreviewBuilder.render` **AND** the parallel `core/claude` `PayloadBuilder` with a **tools section** disclosing each external tool's name/description + a statement that queries will egress. Redaction goldens (`ChatPreviewBuilderTest` + `PayloadBuilderTest`): (a) tool defs present when enabled; (b) **byte-identical/absent** when `tools` default empty; (c) local-only `search_my_library` path byte-identical; (d) a re-fed `tool_result` that re-enters a later request's messages is disclosed.

**Inline activity log (the real disclosure surface for mid-loop queries):** because the model mints NEW queries mid-loop that no pre-stream confirm can show, a **persistent inline activity log** carries the actual egressing query + source + an `egress: Boolean` flag. Rendered as a compact in-transcript bubble ("Searched Semantic Scholar: \"diffusion transformers\"") in chronological position between the user turn and the final assistant answer, updating live. New `AskRole.TOOL` variant + a tool payload on `AskMessage`, driven by the new `ChatChunk` activity signals mapped in `AskViewModel.startStream`'s `when(chunk)`; touches `AskBubble`'s `when(role)`, both hosts (`AskSheet` + `ConversationHost`), and the `ConversationMarkdown` export decision. **Local `search_my_library` DOES render an activity bubble** (trust/consistency) but with `egress = false`.

## 10. Red-line contract

- **Host-gating S2 (PT.3) — reverses a prior decision.** `SemanticScholarClient` **moves** from the bare (ungated) client onto the `@ArxivClient` gated client. This **reverses** the 2026-06-27 HUMAN.md decision "S2 stays on the bare client" (whose own override was "Direct S2 onto the gated client") — the reversal is a security tightening: `api.semanticscholar.org` is already allowlisted, but the interceptor never fires today, so the gate is aspirational. `/s2/` stays **exempt** from the `NoDirectNewCall`/`ArxivRateLimiter` structural rule (it self-spaces via its 1.2s mutex). Verify `CitationSyncWorker` still spaces after the swap.
- **Host-gating chemRxiv (PT.4) — new host, pre-approved.** Add exactly `"chemrxiv.org"` to `AllowedHosts` (+ `AllowedHostsTest`/`AllowedHostsInterceptorTest`). New `@ArxivClient` gated client, own polite mutex (**not** the 3s limiter). Verify **no off-host CDN redirect** on asset PDFs — the interceptor fires per redirect hop and would BLOCK an off-host asset origin.
- **Backup exclusion — allowlist DTO + extended forbidden list.** `ArxiverBackup` is an **explicit allowlist DTO** (verified — exactly six fields: `schema/exportedAt/papers/follows/collections/routines`, `BackupManager.kt:42–49`). A new `tool_invocations` **table cannot auto-leak**; it only leaks if someone explicitly adds a field to a backup DTO. *(The "recursive descriptor test auto-leaks any new table" framing is **inverted** — do not repeat it.)* Requirement: **do not** add any tool/query field to a backup DTO, and keep the top-level-six pin golden. Additionally, the recursive `forbidden`-name walk (`BackupManagerTest.kt:229`) currently forbids only `["chat","session","message","conversation"]` — verified it would **PASS** a field named `tool`/`invocation`/`query`/`activity`. **PT.0 extends the forbidden list** to add `"tool"`, `"invocation"`, `"query"`, `"activity"` so a mis-added tool-activity field is caught at any nesting depth. This closes a hole in the strongest existing safety test, precisely where P-Tools writes new PII (model-derived query strings).
- **BYOK S2 key (PT.3, optional).** The optional free S2 API key reuses `AiKeyVault` (`EncryptedSharedPreferences`, AES256) verbatim — add a `ProviderId.SEMANTIC_SCHOLAR` constant (verify no `ProviderId` iterator assumes chat-provider-ness: `ProviderRegistry`, `DeviceCapabilityProbe`) and feed `SemanticScholarClient.apiKey` via the same lazy-supplier lambda (`{ aiKeyVault.get(ProviderId.SEMANTIC_SCHOLAR) }`), sent as `x-api-key`. Key **never** in DB / logs / backup / fixtures.
- **Rate limits (non-negotiable).** Every external tool fetch routes through the shared limiter/mutex: arXiv ≥3s (`ArxivRateLimiter`), S2 1.2s mutex, chemRxiv polite mutex. A multi-call turn **serializes** through these; no bypass "just for one call."

## 11. Pre-turn RAG coexistence + on-device degrade

- **On-device degrade (automatic).** `supportsTools == false` ⇒ the registry never attaches tools/executor ⇒ the existing single-shot `emitAll(engine.generate())` path ⇒ no `ToolUse` is ever emitted ⇒ the loop terminates after one turn. Matches "on-device degrades to no-tools."
- **Pre-turn RAG coexistence — OPEN DECISION (resolved at PT.1, not PT.0).** The design pass proposed **suppressing pre-turn RAG** when tools are enabled for a cloud chat (pass empty chunks; the model drives retrieval via `search_my_library`), to avoid double-grounding + double token cost. **Personal-validation caveat:** the `[n]` citation contract is built from `assembled.citedChunks` (`ChatRepository.kt:155`); suppressing RAG orphans the `[n]` refs unless citations are re-sourced from tool results. This is a real PT.1 rework to validate against the citation code **then**. **PT.0 leaves pre-turn RAG untouched** — its fake echo tool grounds nothing, so suppression is irrelevant there. PT.1 decides between (a) suppress + re-source citations from tool results, or (b) keep pre-turn RAG and let tools *augment* it. When tools are OFF or on-device, today's fixed pre-turn RAG is unchanged.

## 12. Failure & cancellation semantics

- **Finalize across turns.** `ChatToolLoop` keeps the in-flight message list in the repo scope so `ChatRepository`'s `NonCancellable` finalize (`ChatRepository.kt:276–282`) sees it: a cancelled turn → the partial assistant text persists `incomplete`; an `AiException` → `error`; **never** a dangling `tool_use` without a matching result, and **never** a half-persisted session.
- **Tool-execution errors** map to a `ToolResult{ isError:true, contentJson:<message> }` fed back to the model (so it can recover) — not a thrown exception that aborts the turn. A transport failure inside a tool surfaces as `AppResult.Failure` → an error `ToolResult`.
- **429 / rate-limit** from a tool host surfaces honestly (`AppError.RateLimited`) as an error `ToolResult`; the shared limiter already prevents self-inflicted bursts.
- **SSE silent-drop-hang guard.** Both providers today `runCatching{}.getOrNull() ?: continue` (`AnthropicProvider.kt:89`, `GeminiProvider.kt:82`). Tool-block parsing added into that path must **not** swallow a malformed/partial `tool_use` and hang the loop awaiting a result that never comes — explicit handling + partial/interleaved/malformed SSE tests, plus a loop-level timeout guard.

## 13. Testing strategy

- **Loop unit tests** (fake single-shot provider + fake executor): single tool; **parallel** tools (one assistant + one TOOL turn); **cap-hit** → tools-omitted final round (no dangling `tool_use`); dangling-guard; per-result truncation; cancel/error finalize across a mid-loop interruption.
- **SSE parse tests** (Anthropic + Gemini): `tool_use` block accumulation incl. **partial / interleaved / malformed** (no silent-drop hang); parallel blocks/parts.
- **`supportsTools` gating test:** external defs never attach to an on-device provider; the loop terminates after one turn.
- **Migration:** `MIGRATION_4_5` `runMigrationsAndValidate` + `MigrationHarnessTest`@v5; `5.json` committed.
- **Redaction goldens:** defs present when enabled; byte-identical when empty; local-only path unchanged; re-fed `tool_result` disclosed. Both `ChatPreviewBuilderTest` + `PayloadBuilderTest`.
- **Backup exclusion:** top-level-six pin still green; **forbidden-name walk extended** to `tool/invocation/query/activity` and green; no tool field on any backup DTO.
- **Rate-limit under a multi-call turn:** arXiv ≥3s serialized; S2 1.2s mutex; chemRxiv polite mutex; S2 now behind the `@ArxivClient` interceptor; `CitationSyncWorker` still spaces.
- **Per-tool handler tests** (PT.1–PT.4): local hybrid hits + keyword-only degrade + `includeNotes` gate (PT.1); `import_to_library` persists metadata before save + idempotent (PT.2); S2 gated + parse + BYOK-key-present/absent + non-arXiv-not-importable (PT.3); `chemrxiv.org` allowed / off-host redirect blocked (PT.4).
- **Device (`VERIFICATION.md`, never blocks `[x]`):** live tool dispatch; activity-log render; real arXiv/S2/chemRxiv egress under the confirm; BYOK S2 key round-trip; a full agentic turn (search → reason → import → open in reader).

## 14. Decision log & deferrals

**Locked decisions (this spec):** loop-above-the-provider · three additive default-off `:core:ai` fields + `supportsTools` · **`ChatMessage.toolCalls` carries the assistant `tool_use` side** (personal-validation addition — the plan named only `toolResults`) · `tool_invocations` table FK'd to the assistant message (not new chat_messages roles) · `MAX_TOOL_ITERATIONS = 5`, cap-hit → tools-omitted final round · per-conversation consent + inline activity log (not per-call gate) · S2 moved onto the gated client (reverses 2026-06-27) · chemRxiv `chemrxiv.org` new host · backup forbidden-list extended.

**Deferrals (recorded, routed — never silently dropped):**
- **On-device tool-use** (LiteRT-LM experimental flag) → backlog: flip `supportsTools=true` for a future on-device tier + teach Gemma/Qwen engines to emit `ToolUse`.
- **S2 author-search & snippet-search** → backlog (approved vision explicitly defers).
- **Generic (non-arXiv) import** → `HUMAN.md`: the arXiv-id-keyed `Paper`/library schema can't hold an S2/chemRxiv result with no arXiv `externalId`; needs a schema decision (generic paper id + doi/source) — Co-Founder steer. PT.2/PT.3 import only arXiv-resolvable results.
- **Chunk-level library tool** (`RetrievalScope.Library` + a `chunksForLibrary` DAO) → backlog: finer citations than PT.1's paper-level path; overlaps the parked "Library-wide KB flag." PT.1 satisfies the library-wide-KB intent agentically at **paper** granularity.
- **`recommend_papers` as a first-class tool** → include as an S2 endpoint in PT.3 if cheap, else backlog.
- **Live tool-arg streaming** → backlog (MVP buffers `ToolUse` to complete).
- **Concurrent tool execution** → backlog (MVP serializes through the shared limiters; a bounded-concurrent, per-host-mutex-respecting executor is an optimization).

## 15. Subphase plan & CHECKPOINT

See the ROADMAP **Phase P-Tools** section for the PT.0–PT.4 rows + **CHECKPOINT P-Tools** criteria. Sequencing de-risks by egress: PT.0 lands the loop + consent/disclosure + migration with a **fake local tool (zero egress)**; PT.1 proves the architecture with `search_my_library` (**still zero egress**); only **PT.2** opens the first external byte. Each subphase is one PR, gated by a planning pass → personal adversarial validation → post-diff adversarial review → green `./gradlew build` → CI → self-merge.
