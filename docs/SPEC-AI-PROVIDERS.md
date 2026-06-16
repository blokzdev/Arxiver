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
- **Tier 2 — downloaded Gemma 4 E2B** (0.84 GB text-only, Apache-2.0, `.litertlm`) via **LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android`): `Engine(EngineConfig(modelPath, backend = CPU/GPU))` → `Conversation.sendMessageAsync(Message.of(...))` → Kotlin `Flow`. `engine.initialize()` is slow (~seconds) → background dispatcher, mutex-guarded session (mirror `EmbeddingService`). Model downloaded with the `ModelDownloader` (URL + SHA-256 + atomic rename + `ModelState`) under the unmetered `SyncScheduler` constraint. Gate on a RAM floor.
- **Tier 3 — BYOK cloud** (always available with a key, device-independent, best quality / largest context):
  - **Claude** — Anthropic Messages API: `POST https://api.anthropic.com/v1/messages`, headers `x-api-key`, `anthropic-version`; `stream:true` → SSE.
  - **Gemini** — Gemini Developer API: `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent` with the user's key (`x-goog-api-key`).
  - **Explicitly not Firebase AI Logic / `firebase-ai`**: that SDK proxies a *developer's* Firebase-project key (with App Check), which is not end-user BYOK. The deprecated `generative-ai-android` SDK is also avoided. Hitting the REST endpoints directly over OkHttp matches Arxiver's existing light client pattern (`ArxivApiClient`/`SemanticScholarClient`) and avoids a Firebase dependency.

**Capability detection & degradation.** `DeviceCapability` reads total RAM (`ActivityManager.MemoryInfo.totalMem`) and ML-Kit GenAI availability → an `InferenceTier` recommendation. Default on-device order: **Gemma 4 E2B → Nano → cloud BYOK → none** (Gemma preferred over Nano when downloaded — higher-quality/longer output; Nano is the zero-download fallback and the recommendation when Gemma isn't installed). The user picks a provider in Settings, and when both on-device engines are ready can override the engine (Auto/Gemma/Nano, persisted as `preferred_ondevice_tier`). If a tier becomes unavailable (model deleted, key cleared, device unsupported), fall back down the order with a clear message.

## 4. Key storage

Generalize the existing single-purpose `TokenVault` (routine alias → token) into a **multi-key
vault**: `(ProviderId → encrypted key)` in `EncryptedSharedPreferences` (AES256-GCM/SIV, as
today). Either extend `TokenVault` with provider-scoped methods or add a sibling `AiKeyVault`
reusing the same crypto. **Red lines (unchanged):** keys never in the DB, logs, exports,
backups, or fixtures; the structural-redaction tests stay green. Key entry mirrors
`RoutineSetupScreen`'s write-only `PasswordVisualTransformation` field; keys are never read
back to the UI.

## 5. Privacy — "what leaves the device" (realized in P2.2)

- On-device tiers (Nano, Gemma) send **nothing** off the device — that's the privacy default;
  `ChatRepository.prepare` flags `isCloud` so the UI skips the preview for on-device.
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
  `generativelanguage.googleapis.com`, and the pinned Gemma 4 model download URL (in addition
  to the existing export.arxiv.org / api.semanticscholar.org / routine URLs / pinned bge URL).

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
  (privacy/cost). On-device readiness comes from the engines (`isReady()`), not `isConfigured`
  (a key-less provider always reports configured). No usable provider → `NotConfigured` ("configure
  a provider" UI state).
- **stream**: persist the user turn, stream `AiProvider.chat`, persist the assistant turn with a
  `status` (`incomplete` while streaming → `complete` on done; `error` on `AiException`; cancellation
  leaves the partial `incomplete`).

## 7. Testing

- **Cloud transports**: `AnthropicProvider`/`GeminiProvider` against `MockWebServer` (success, SSE stream parse, auth-rejected → typed error, offline/5xx → `AppError`).
- **Redaction**: golden tests that the cloud request body contains exactly the intended context and no gated user data / no key (mirror `PayloadBuilderTest`).
- **Tier selection**: `DeviceCapability`/`InferenceTier` unit tests over RAM + AICore-availability inputs and the degradation order.
- **On-device** (LiteRT-LM init, Gemma generation, Nano availability, real RAM/NPU behavior): device-bound → tracked in `VERIFICATION.md` under a new AI-inference tier section; not in CI.

## 8. Extensibility & versioning

Adding a provider = implement `AiProvider` + register it in `AppModule`; the chat UI and RAG
are unchanged. Per-provider wire formats and model ids are pinned in the provider impl. Any
breaking change to the chat-history or knowledge-base schema follows the Room migration red
line (migration + committed schema JSON), specified in the P2 subphase.
