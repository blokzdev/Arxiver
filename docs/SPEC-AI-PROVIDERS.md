# SPEC-AI-PROVIDERS — multi-provider + on-device AI platform (P1)

> Status: **architecture spec, drafted 2026-06-14.** Grounds the P1 (AI Provider Platform)
> and P2 (chat / RAG) subphases; each subphase is planned individually before building.
> This is distinct from the Claude **Routines** dispatch bridge (SPEC-CLAUDE-BRIDGE) — that
> is an *outbound* trigger to a repo-scoped cloud agent; this is *conversational* inference.

## 1. Goal & principles

Give Arxiver conversational/generative AI (chat-with-paper, summaries, RAG over a knowledge
base) **without compromising the local-first, no-telemetry stance**:

- **BYOK (bring your own key)** for cloud — the app ships with no LLM keys and bears no LLM cost; the user supplies their own provider key.
- **On-device by preference** where the hardware allows — zero cost, offline, nothing leaves the device.
- **Provider-agnostic** — one interface; Claude, Gemini, and on-device are interchangeable behind it; adding a provider is additive.
- **Graceful degradation** — pick the best tier the device supports; degrade predictably.
- **Privacy red lines hold** — keys only in `EncryptedSharedPreferences`; an explicit "what leaves the device" confirm before any cloud call; no analytics; allowed-hosts list extended only to the provider endpoints below.

## 2. Provider abstraction (`:core:ai`)

A new `:core:ai` module (layer: `:core:*`, no `:app` deps). Core type:

```
interface AiProvider {
    val id: ProviderId                 // CLAUDE | GEMINI | ON_DEVICE
    val capability: ProviderCapability // contextTokens, streaming, onDevice, requiresKey
    suspend fun chat(request: ChatRequest): Flow<ChatChunk>   // streamed tokens
}
```

- `ChatRequest` = provider-neutral messages (`ChatMessage(role, content)`) + the **retrieved context** (RAG chunks) + generation params. Building the prompt from messages+context is the provider impl's job (each provider has its own wire format).
- `ChatChunk` = a streamed delta (`text`) or terminal (`done`, usage, stop reason). Errors cross the boundary as `AppResult`/`AppError` (reuse `:core:common`), never exceptions.
- Streaming is first-class (`Flow`) so the UI renders tokens as they arrive; on-device LiteRT-LM already yields a Flow, cloud SSE maps to one.

Implementations: `AnthropicProvider`, `GeminiProvider`, `OnDeviceProvider` (LiteRT-LM and/or ML Kit GenAI). A `ProviderRegistry` exposes the available providers and the selected default; DI registers each in `AppModule`.

## 3. Inference tiers & selection

Three tiers, best-first; the platform detects capability and recommends, the user can override.

- **Tier 1 — system Gemini Nano** (ML Kit GenAI **Prompt API**, `com.google.mlkit:genai-prompt`). Flagship-allowlisted hardware (Pixel 9, Galaxy S25, etc.); no download, OS-managed lifecycle, NPU-accelerated, free. Gate on `checkStatus()`. The zero-setup option, but its Prompt API caps output (~256 tokens, EN/KO validated), so it sits **below** a downloaded Gemma in the default order (see below).
- **Tier 2 — downloaded Gemma 4 E2B** (~2.59 GB text-only, standard CPU/GPU build, Apache-2.0, `.litertlm`) via **LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android`): `Engine(EngineConfig(modelPath, backend = CPU/GPU))` → `Conversation.sendMessageAsync(Message.of(...))` → Kotlin `Flow`. `engine.initialize()` is slow (~seconds) → background dispatcher, mutex-guarded session (mirror `EmbeddingService`). Model downloaded with the `ModelDownloader` (URL + SHA-256 + atomic rename + `ModelState`) under the unmetered `SyncScheduler` constraint. Gate on a RAM floor.
- **Tier 3 — BYOK cloud** (always available with a key, device-independent, best quality / largest context):
  - **Claude** — Anthropic Messages API: `POST https://api.anthropic.com/v1/messages`, headers `x-api-key`, `anthropic-version`; `stream:true` → SSE.
  - **Gemini** — Gemini Developer API: `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent` with the user's key (`x-goog-api-key`).
  - **Explicitly not Firebase AI Logic / `firebase-ai`**: that SDK proxies a *developer's* Firebase-project key (with App Check), which is not end-user BYOK. The deprecated `generative-ai-android` SDK is also avoided. Hitting the REST endpoints directly over OkHttp matches Arxiver's existing light client pattern (`ArxivApiClient`/`SemanticScholarClient`) and avoids a Firebase dependency.

**Capability detection & degradation.** `DeviceCapability` reads total RAM (`ActivityManager.MemoryInfo.totalMem`) and ML-Kit GenAI availability → an `InferenceTier` recommendation. Default on-device order: **Gemma 4 E2B → LIGHT (Qwen3-0.6B) → Nano → cloud BYOK → none** (Gemma preferred when downloaded — higher-quality/longer output; the **LIGHT tier** is `Qwen3-0.6B.litertlm`, 614 MB INT8, filename+SHA-256-pinned, RAM floor `LIGHT_RAM_FLOOR_MB = 3072` reaching the 3–4 GB segment Gemma's 4096 floor excludes, `richness = PLAIN`, stored in its own `models/light` dir so the two `.litertlm` files never purge each other; Nano is the zero-download fallback). The user picks a provider in Settings, and when **≥2 on-device engines are ready** can override the engine (chips per ready tier: Auto/Gemma/Qwen light/Nano, persisted as `preferred_ondevice_tier`). If a tier becomes unavailable (model deleted, key cleared, device unsupported), fall back down the order with a clear message.

## 4. Key storage

Generalize the existing single-purpose `TokenVault` (routine alias → token) into a **multi-key
vault**: `(ProviderId → encrypted key)` in `EncryptedSharedPreferences` (AES256-GCM/SIV, as
today). Either extend `TokenVault` with provider-scoped methods or add a sibling `AiKeyVault`
reusing the same crypto. **Red lines (unchanged):** keys never in the DB, logs, exports,
backups, or fixtures; the structural-redaction tests stay green. Key entry mirrors
`RoutineSetupScreen`'s write-only `PasswordVisualTransformation` field; keys are never read
back to the UI.

## 5. Privacy — "what leaves the device" (realized in P2.2)

- On-device tiers (Nano, Gemma, Qwen light) send **nothing** off the device — that's the privacy
  default; `ChatRepository.prepare` flags `isCloud` so the UI skips the preview for on-device.
- Before any **cloud** call, the `ChatPreviewBuilder` produces the exact body — the system
  instruction + messages (retrieved chunks already folded in) — for a confirm sheet, using the
  `explicitNulls = false` structural-redaction config (mirrors `PayloadBuilder`). The provider key
  travels only in the HTTP header, so it is **structurally absent** from this body; note-derived
  chunks are gated by `includeNotes` in `ChatContextAssembler`, the same way dispatch gates notes.
  A golden test (`ChatPreviewBuilderTest`) asserts the body carries exactly the intended context
  and no key / no gated note content. The per-paper **Ask sheet** (P2.3) renders this preview as a
  confirm before a cloud reply and streams on-device with no confirm; the **prefer-on-device**
  toggle (Settings) is the opt-in that routes to on-device when ready.
- No-telemetry red line holds. Allowed network hosts extend only to: `api.anthropic.com`,
  `generativelanguage.googleapis.com`, and the pinned **Gemma 4 + Qwen3-0.6B** model download
  URLs (both on `huggingface.co`, the already-allowlisted pinned-model host — the light tier
  adds **no new egress host**) — in addition to the existing export.arxiv.org /
  api.semanticscholar.org / routine URLs / pinned bge URL.

## 6. RAG integration (feeds P2)

Retrieval is **provider-agnostic and on-device** (SPEC-SEARCH §8): embed the query with the bge
`EmbeddingService.embedQuery`, then `RagRetriever.retrieve` blends a cosine leg + a chunk-FTS BM25
leg over a single paper or a user-curated **knowledge base** (= an existing **Collection**;
abstract+notes now, full PDF text once P3 lands). Only the retrieved chunks + the question are
placed in the `ChatRequest` context and sent to the chosen provider. (No provider offers an
embeddings API we depend on — retrieval never leaves the device, only generation may.) Chunking
+ the chunk-embedding schema are in the P2.1 specs (SPEC-SEARCH §8 / SPEC-DATA). Plan: `docs/P2-PLAN.md`.

## 6a. Chat orchestration (P2.2)

`ChatRepository` turns a question into a grounded, streamed answer and is the seam P2.3/P2.4 wrap:
- **prepare** (read-only): embed query → `RagRetriever.retrieve` → `ChatContextAssembler` folds
  chunks + prior turns + the question under the provider's `contextTokens` budget (char/4 proxy;
  oldest history dropped first, then lowest-scored chunks; the question is never dropped) → resolve
  the provider → build the §5 preview. Declining the preview persists nothing.
- **provider resolution** (`ProviderResolver`): respect the user's `selectedAiProvider` by default;
  a **`preferOnDeviceWhenReady`** opt-in (Settings) makes an on-device engine win whenever ready
  (privacy/cost). On-device readiness is **`OnDeviceProvider.isReady()`** — true when ANY wired
  engine is ready — and the resolver's `onDeviceReady` seam MUST delegate there; for readiness
  resolution, engines are **never enumerated outside `OnDeviceProvider`'s wired DI list** (a
  hand-enumerated `gemma || nano` seam went stale when the Qwen light tier landed, leaving
  Qwen-only devices at `NotConfigured` — the 2026-07-03 hotfix). Readiness never comes from
  `isConfigured` (a key-less provider always reports configured). Display/eligibility surfaces
  (`DeviceCapabilityProbe`, Settings) legitimately enumerate per-engine state — resolution doesn't.
  No usable provider → `NotConfigured` ("configure a provider" UI state).
- **stream**: persist the user turn, stream `AiProvider.chat`, persist the assistant turn with a
  `status` (`incomplete` while streaming → `complete` on done; `error` on `AiException`; cancellation
  leaves the partial `incomplete`).
- **Scope** is uniform: per-paper Ask uses `RetrievalScope.Paper`; **collection (KB) chat** (P2.4)
  uses `RetrievalScope.Collection` over the collection's papers' chunks, with **ensure-embedded on
  open** (`RagIndexer.indexCollection` indexes only papers missing current-model chunks). Sessions
  are listed/resumable via `ChatDao.observeAllSessions` (chat-history screen). No new hosts/schema.

## 6b. Output richness ladder (P-Atlas PA.2)

A turn's system prompt is shaped per **engine**, not per provider — replacing the old binary
"cloud vs on-device" gate with `OutputRichness { PLAIN, STRUCTURED, FULL }` (`:core:ai`), a
**non-defaulted** field on `ProviderCapability` so every provider declares it:

- **PLAIN** — the base `SYSTEM_PROMPT` only (which already invites Markdown tables). System Gemini
  Nano and the light tier (Qwen3-0.6B, PA.3).
- **STRUCTURED** — base + a compact table-focused nudge **with a 1-shot example**, and explicitly
  **no LaTeX / no Mermaid**. Gemma E2B. Rationale (2025–2026 research): a ~2B model emits valid
  Markdown tables ~70–85% of the time (a 1-shot exemplar lifts it), but valid Mermaid only ~60%
  (MermaidSeqBench) and LaTeX ~9.7% error — so diagrams/math stay cloud-only.
- **FULL** — base + the LaTeX + Mermaid invitation (cloud BYOK: Claude, Gemini). **Byte-identical to
  the pre-PA.2 cloud path** (the redaction goldens are untouched).

**Resolution.** Richness must reflect the engine that will actually stream. `OnDeviceProvider`'s
static capability is a PLAIN placeholder; `resolveRichness()` runs the **same** ready-engine pick as
`chat()` (shared `pickReadyEngine`), and `ChatRepository.prepare` overrides the on-device capability
(`copy(richness = …)`) before assembling. A microscopic prepare-vs-stream readiness flip is harmless
(STRUCTURED differs from PLAIN only by the table nudge; the base prompt invites tables for either).

PA.2 is principally the **infrastructure** the light tier (PA.3) and the structured-output work
(PA.4) build on. The *valid-by-construction* on-device win is **not** grammar/constrained decoding:
the PA.0 spike (2026-06-26) found LiteRT-LM's **Kotlin** binding doesn't expose it (only C++ does;
upstream issue [#1662](https://github.com/google-ai-edge/LiteRT-LM/issues/1662) is open), and the
research found constraints *hurt* content at our model scale ("Constraint Tax", 2026). Instead,
**PA.4 = "app-draws-the-structure"**: the model emits a low-syntax sentinel-delimited intermediate,
a pure-Kotlin parser builds a typed `ComparisonTable`, and a deterministic renderer emits a
guaranteed-valid table (with a confidence-gated bulleted-list fallback) — extending PA.1's
deterministic-artifact thesis to tables. STRUCTURED is the seam it hooks into.

## 7. Testing

- **Cloud transports**: `AnthropicProvider`/`GeminiProvider` against `MockWebServer` (success, SSE stream parse, auth-rejected → typed error, offline/5xx → `AppError`).
- **Redaction**: golden tests that the cloud request body contains exactly the intended context and no gated user data / no key (mirror `PayloadBuilderTest`).
- **Tier selection**: `DeviceCapability`/`InferenceTier` unit tests over RAM + AICore-availability inputs and the degradation order.
- **Provider resolution**: `ProviderResolver` policy unit tests, **plus a composition regression test** that wires the REAL `OnDeviceProvider` into the `onDeviceReady` seam exactly as DI does, with a sole-ready engine (the Qwen-only device state), asserting resolution to `ON_DEVICE` (the 2026-07-03 hotfix pin — the boolean-seam policy tests alone cannot see a stale DI wiring).
- **On-device** (LiteRT-LM init, Gemma generation, Nano availability, real RAM/NPU behavior): device-bound → tracked in `VERIFICATION.md` under a new AI-inference tier section; not in CI. Device verification of an additive tier must include the **tier-alone** configuration, not just coexistence (K10's Gemma-coexisting run masked the resolver bug).

## 8. Extensibility & versioning

Adding a provider = implement `AiProvider` + register it in `AppModule`; the chat UI and RAG
are unchanged. Adding an on-device **engine** = append it to `OnDeviceProvider`'s engines list
in `AppModule`: chat routing and resolver readiness both derive from that single list
(`pickReadyEngine()` / `isReady()`). The download/capability/settings surfaces (qualified
`ModelDownloader` + worker, `DeviceCapability` floor, `TierSelector` order, settings UI) are
separate additive seams — see ROADMAP PA.3a/PA.3b for the template. Per-provider wire formats
and model ids are pinned in the provider impl. Any breaking change to the chat-history or
knowledge-base schema follows the Room migration red line (migration + committed schema JSON),
specified in the P2 subphase.
