# P2-PLAN — Chat-with-paper & Knowledge Base (RAG)

**Status:** Plan · drafted 2026-06-15 · target release **v2**. Overarching map for the P2
phase; each subphase (P2.1–P2.4) is planned in detail and shipped as its own green-build PR.

> This plan sits **on top of P1** (the AI Provider Platform — `docs/SPEC-AI-PROVIDERS.md`) and
> reuses the existing on-device retrieval stack (`docs/SPEC-SEARCH.md`, `docs/SPEC-DATA.md`).
> It is distinct from the Claude **Routines** dispatch bridge (`docs/SPEC-CLAUDE-BRIDGE.md`):
> that is an *outbound* trigger to a repo-scoped cloud agent; this is *conversational* inference
> over the user's own provider key (or on-device model). Detailed schema/contract edits land in
> the relevant SPEC with each subphase's PR — this doc is the spine, not the contract.

## 1. Goal & scope

Give Arxiver **chat-with-paper, summaries, and chat over a curated knowledge base**, grounded
in the user's library by on-device retrieval (RAG) — without breaking the local-first,
no-telemetry stance. The PRD lists exactly this as the top v2 candidate ("In-app chat-with-paper
is a candidate for v2"; full-text PDF indexing is a separate v2 candidate that lands in P3 and
feeds this engine later).

What P2 delivers:

- **Per-paper "Ask"** — a streaming chat grounded in a single paper (abstract + notes now;
  full PDF text once P3 lands), plus one-shot **summaries**.
- **Knowledge-base chat** — chat grounded across a **Collection** (see §3), retrieving over all
  its papers' chunks.
- **On-device RAG** — chunk + embed library text, retrieve top-K locally, and place only the
  retrieved chunks + the question into the prompt. Generation runs on the P1-selected provider
  (on-device preferred when ready, else the user's cloud key).
- **Chat history** — sessions + turns persisted locally (new tables/DAO).

Privacy red lines (unchanged, restated): provider keys only in `EncryptedSharedPreferences`;
**retrieval never leaves the device** — only the retrieved chunks + the question may, and only
after an explicit **"what leaves the device"** confirm before any cloud call; no analytics; the
allowed-hosts list is **not** extended by P2 (the provider endpoints were already added in P1).

Out of scope for P2: routine *result round-trip* (research-confirmed impractical — see
SPEC-CLAUDE-BRIDGE / Decision log 2026-06-14); full-text PDF extraction (P3); a library-wide
KB flag (backlogged, §3); per-provider model pickers (P1 backlog).

## 2. Architecture overview

P2 is **additive** and layers cleanly onto existing modules — no changes to the P1 provider
contract are required.

```
Paper Detail / Collection  ── UI (Compose)
        │  Ask sheet / KB chat screen
        ▼
   ChatViewModel ──────────────┐
        │                      │ resolve provider
        ▼                      ▼
  ChatRepository        ProviderRegistry + AiProviderStore + TierSelector   (P1, :core:ai)
        │  assemble ChatRequest(messages = system + retrieved chunks + history + question)
        │                      │
        ▼                      ▼
   RagRetriever          AiProvider.chat(): Flow<ChatChunk>   ── on-device OR cloud
        │  embed query → topK over chunk store (+ HybridFusion)
        ▼
  chunk-embedding store + chat-history store   (:core:database, new tables)
        ▲
        │ chunk + embed (abstract/notes)
  EmbeddingService.embedPassages  (:core:ml)        VectorIndex.topK / HybridFusion  (:core:search)
```

The hard boundary: **retrieval is on-device-only** (no provider offers an embeddings API we
depend on); **generation** is the only thing that may reach a cloud provider, and only the
retrieved chunks + question travel — gated by the privacy preview (§6).

## 3. Knowledge-base model

A "knowledge base" — the curated paper set a per-KB chat covers — **is an existing Collection.**
This was chosen deliberately (Decision log 2026-06-15):

- Collections are already user-curated paper sets with full Library UX (`collections` /
  `collection_papers` in SPEC-DATA §2). Reusing them means **no new curation UI and no schema
  change for membership** — only the chat-history + chunk tables are new.
- A chat session is scoped: `scope = PAPER` (per-paper Ask, needs no KB at all) or
  `scope = COLLECTION` (KB chat, retrieves over that collection's papers' chunks).

**Backlog (revisit later):** a single **library-wide KB flag** (`library_entries.in_kb`) for a
"chat my whole library" mode, kept out of the v2 first cut to avoid a schema change and a second
curation concept while collections already serve the need (tracked in ROADMAP v2 infra backlog).

## 4. Subphase breakdown

Dependency-ordered. Each is a Plan-tool session + a green-build PR; detailed contracts land in
the named SPEC in the same PR.

### P2.1 — On-device RAG retrieval foundation
*Layer:* `:core:database` + `:core:ml`/`:core:search`. No UI, no LLM. Fully CI-testable.

- **Chunker** — split a paper's `title + abstract` (and each note) into token-bounded chunks
  (BGE's 512-token window per SPEC-SEARCH §6; sentence/paragraph aware, with overlap TBD in the
  subphase). Pure function, unit-tested.
- **Chunk-embedding store** — a new `chunk_embeddings` table (BLOB float32[384], `paper_id`,
  source kind [`abstract`/`note`], ordinal, text; model-guarded like `embedding_meta`), with a
  Room `Migration` + committed schema JSON (red line: no destructive migration). Embedding via
  the existing `EmbeddingService.embedPassages`.
- **`RagRetriever`** — `embedQuery` → cosine top-K over the chunk store **scoped** to a paper or
  a collection's papers (reusing the `VectorIndex` chunked-scan approach), with an optional
  `HybridFusion` keyword blend over chunk text. Returns retrieved chunks (text + provenance).
- **Worker** — embed chunks for asked/KB papers on unmetered (mirror `EmbeddingWorker` /
  `SyncScheduler`).
- **DoD/testing:** chunker + retriever pure-unit tests; chunk-DAO + migration Robolectric tests;
  worker test. SPEC edits ride here: **SPEC-SEARCH §8** (chunking/retrieval) + **SPEC-DATA**
  chunk-embedding table.

### P2.2 — Chat orchestration + history + "what leaves the device"
*Layer:* `:core:database` + app domain. Pure-JVM testable with a fake `AiProvider` + in-memory DB.

- **Chat-history store** — `chat_sessions(id, scope, scope_id, provider_id, created_at,
  last_message_at)` + `chat_messages(id, session_id, role, content, created_at)` + DAO; Room
  `Migration` + schema JSON.
- **`ChatRepository`/use-case** — assemble the `ChatRequest` (system prompt + retrieved chunks +
  prior turns + question; RAG context folds into `messages` per the `ChatRequest` doc comment),
  **resolve the provider** via `ProviderRegistry` + `AiProviderStore` + `TierSelector`
  (on-device preferred when ready, else the selected cloud key), stream `Flow<ChatChunk>`,
  persist turns, map `AiException` → UI state.
- **"What leaves the device" builder** — reuse `core/claude` `PayloadBuilder`'s structural
  redaction (`explicitNulls = false`) to produce the exact messages + chunks preview for cloud
  calls; notes-derived chunks gated the same way dispatch gates notes.
- **DoD/testing:** repository tests (provider resolution + fallback, persistence, stream
  mapping); a **redaction golden test** that the cloud request body contains exactly the intended
  context and no key / no gated user data (mirror `PayloadBuilderTest`). SPEC edits ride here:
  **SPEC-DATA** chat tables; **SPEC-AI-PROVIDERS §5** privacy preview realized.

### P2.3 — Per-paper "Ask" sheet + summaries *(first user-facing milestone)*
*Layer:* `:app` (Paper Detail).

- Streaming **Ask** sheet on Paper Detail (renders `ChatChunk.Delta` as they arrive); a
  **summarize** action; provider/on-device indicator.
- **"What leaves the device"** confirm before any cloud call (skipped for on-device — nothing
  leaves); offline/error/empty/loading states; light/dark previews; TalkBack labels on
  actionables (per the UI DoD).
- **DoD/testing:** ViewModel tests (streaming accumulation, cloud-confirm gate, error mapping);
  previews. Device-bound behavior → `VERIFICATION.md` §K.

### P2.4 — Collections as chattable KBs + chat history
*Layer:* `:app` (Collection view).

- **Per-collection chat** (`scope = COLLECTION`) retrieving over the collection's papers' chunks;
  entry point from a collection; ensure-embedded on open.
- **Chat history** — resumable session list (per paper / per collection).
- **DoD/testing:** ViewModel tests (scoped retrieval, history resume); previews. Device-bound →
  `VERIFICATION.md` §K.

### CHECKPOINT P2
CI green; retrieval/chat/redaction covered by unit + Robolectric tests; **keys, routine tokens,
and gated notes are absent from chat payloads and exports** (structural tests); the device-bound
chat/RAG checks are tracked in `VERIFICATION.md` §K (device-only verification never blocks `[x]`
but must be recorded there, per the CLAUDE.md loop).

## 5. Data & schema preview (intent — DDL lands per subphase)

Two new local stores, both under the Room-migration red line (migration + committed schema JSON,
no destructive migration); neither is exported in backups (chunk embeddings are re-derivable;
chat history is local conversation):

- **`chunk_embeddings`** (P2.1) — per-paper text chunks + BLOB embeddings, model-guarded.
- **`chat_sessions` / `chat_messages`** (P2.2) — scoped chat history (`PAPER` | `COLLECTION`).

No change to `library_entries`/`collections` (KB membership reuses `collection_papers`).
Provider keys stay in `EncryptedSharedPreferences` (P1 `AiKeyVault`) — **never** in the DB,
exports, or backups (same red line as routine tokens).

## 6. Privacy — "what leaves the device"

Realizes the preview that P1.1 explicitly deferred to P2 (SPEC-AI-PROVIDERS §5, Decision log
2026-06-15):

- **On-device tiers send nothing off-device** — no preview needed; that's the default.
- **Before any cloud call**, show a "what leaves the device" confirm (reuse the `DispatchSheet`
  pattern + `PayloadBuilder` structural redaction): the exact messages + retrieved chunks that
  will be sent. Notes-derived chunks are gated like dispatch gates notes.
- **No new allowed hosts.** P2 adds none — `api.anthropic.com` and
  `generativelanguage.googleapis.com` were added in P1; retrieval is local.

## 7. Testing & verification

- **CI (JVM/Robolectric):** chunker + retriever (pure), chunk/chat DAOs + migrations
  (Robolectric), `ChatRepository` (fake provider + in-memory DB), redaction golden tests, chat
  ViewModels.
- **Device-bound → `VERIFICATION.md` §K:** real on-device generation offline (no traffic),
  streaming render, privacy-preview fidelity, provider fallback, retrieval relevance over a real
  library, chat-history persistence across restart. CI can't run these; they ride the §K
  checklist + dated log (mirrors §J for P1).

## 8. Cross-references & backlog

- **Builds on:** `docs/SPEC-AI-PROVIDERS.md` (P1 platform, §6 RAG integration), `docs/SPEC-SEARCH.md`
  (retrieval engine + v2 note), `docs/SPEC-DATA.md` (schema + v2 note), `docs/PRD.md` (§5 v2
  candidates).
- **Feeds / fed by:** P3 (full-text PDF extraction → chunks) extends P2's retrieval corpus.
- **Backlog (ROADMAP v2 infra backlog):** library-wide KB flag (`in_kb`); full-text PDF chunks
  (rides P3); per-provider model pickers (P1 backlog).
