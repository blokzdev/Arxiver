# Reference — on-device AI (LiteRT-LM, ML Kit GenAI, Gemma 4)

> External-API reference for the P1.2 on-device inference tiers. Captured from the
> official docs (June 2026) because these libraries/models post-date the assistant's
> training cutoff. Pin exact versions in `gradle/libs.versions.toml` at implementation
> time; treat code below as the shape, not a copy-paste contract. Sources at the bottom.

## Tier 2 — Gemma 4 E2B via LiteRT-LM (downloadable, Apache-2.0)

**Model.** Gemma 4 (released 2026-03-31) is **Apache-2.0** (earlier Gemma generations used
the custom Gemma Terms of Use — Gemma 4 dropped that). The E2B / E4B variants are the
phone/edge sizes, shipped in `.litertlm` for LiteRT-LM. Text-only variants drop the vision
encoder for a smaller footprint.

- Repo: `litert-community/gemma-4-E2B-it-litert-lm` (HuggingFace). A text-only variant
  exists; the full `it` `.litertlm` is ~2.0–2.6 GB on disk. In-memory **weights as low as
  ~0.8 GB** (text-only). Android **CPU runtime memory ≈ 1.36–1.73 GB**.
- Download URL pattern: `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/<file>.litertlm`.
- **Pinned in `GemmaEngine.SPEC` (P1.2b; corrected in F2):** `gemma-4-E2B-it.litertlm`, **2,588,147,712 bytes (~2.59 GB)**, SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` — the standard CPU/GPU build. **Do not use the `-web` variant** (`gemma-4-E2B-it-web.litertlm`): it is WebGPU-only — its single `gpu_artisan` decoder has **no CPU `TF_LITE_PREFILL_DECODE` graph**, so on a CPU-backend device (e.g. Galaxy S20, where NPU registration fails) LiteRT-LM loads it but generates **zero tokens** (confirmed in a device bug report, 2026-06-19). The repo also has chipset NPU builds (Tensor G5 / Qualcomm sm8750/qcs8275 / Intel, 2.9–4.0 GB) for specific newer SoCs.
- RAM floor decision: offer Gemma only when total device RAM ≥ ~4 GB (`GEMMA_RAM_FLOOR_MB`).
- **Dependency: `litertlm-android:0.13.1` ships Kotlin 2.3.0 metadata** — consume it from a module built with `-Xskip-metadata-version-check` until the project's Kotlin reaches ≥ 2.2 (compiler that reads 2.3.0 metadata natively).

**Dependency** (`com.google.ai.edge.litertlm:litertlm-android`):
```kotlin
implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release") // pin a concrete version
```

**Kotlin API** (`com.google.ai.edge.litertlm.*`):
```kotlin
val engineConfig = EngineConfig(
    modelPath = "/path/to/model.litertlm",
    backend = Backend.CPU(),            // or Backend.GPU() / Backend.NPU(nativeLibraryDir)
    cacheDir = context.cacheDir.path,   // optional: speeds up 2nd load
)
val engine = Engine(engineConfig)
engine.initialize()                     // SLOW (~up to 10s) → background dispatcher

val conversationConfig = ConversationConfig(
    systemInstruction = Contents.of("You are a research assistant."),
    samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.8),
)
engine.createConversation(conversationConfig).use { conversation ->
    conversation.sendMessageAsync("question")   // Flow<Message>
        .catch { /* map to AppError */ }
        .collect { message -> print(message.text) }
}
engine.close()                          // release resources
```
- Android GPU backend: declare native libs in the manifest. NPU: `nativeLibraryDir = context.applicationInfo.nativeLibraryDir`.
- `sendMessageAsync(contents): Flow<Message>` is the recommended coroutine path; read text via `message.text`.
- Mirror `EmbeddingService`: one `Mutex`, lazy `initialize()` on an injected dispatcher after `ModelDownloader.ensureDownloaded()`.

## Tier 1 — system Gemini Nano via ML Kit GenAI Prompt API (no download, flagship-only)

- OS-managed; **no app-bundled model**. Allowlisted flagships (Pixel 8+/Galaxy S25+/…); the
  latest Nano launched on Pixel 10. **Not supported on unlocked bootloaders.** minSdk 26.
- The general-purpose path is the **Prompt API** (`genai-prompt`); task-specific APIs
  (summarization/proofreading/rewriting/image-description) also exist but Prompt is what
  free-form chat-with-paper needs.
- Limits: input < ~4000 tokens; avoid output > 256 tokens; validated languages EN/KO.

**Dependency (wired in P1.2c):**
```kotlin
implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")
```
Confirmed via AAR inspection: `Generation.getClient(): GenerativeModel`; `suspend checkStatus(): @FeatureStatus Int` (constants on `com.google.mlkit.genai.common.FeatureStatus`); `download(): Flow<DownloadStatus>` (`DownloadStarted.bytesToDownload` / `DownloadProgress.totalBytesDownloaded` / `DownloadCompleted` / `DownloadFailed`); `generateContentStream(String): Flow<GenerateContentResponse>` with text at `response.candidates.first().text`. Safe on non-flagships (checkStatus → UNAVAILABLE → degrade).

**Order decision (P1.2c):** default on-device order is **Gemma → Nano** (Nano's ~256-token output cap makes it the zero-download fallback, not the default); users can override (Auto/Gemma/Nano).

**Kotlin API:**
```kotlin
val model = Generation.getClient()                 // optionally Generation.getClient(GenerationConfig(...))

when (model.checkStatus()) {                        // FeatureStatus
    FeatureStatus.UNAVAILABLE -> { /* device unsupported → degrade to Gemma/cloud */ }
    FeatureStatus.DOWNLOADABLE -> { /* offer download() */ }
    FeatureStatus.DOWNLOADING -> { /* in progress */ }
    FeatureStatus.AVAILABLE -> { /* ready */ }
}

model.download().collect { status ->               // DownloadStatus
    when (status) {
        is DownloadStatus.DownloadStarted -> {}
        is DownloadStatus.DownloadProgress -> {}
        DownloadStatus.DownloadCompleted -> {}
        is DownloadStatus.DownloadFailed -> {}
    }
}

model.generateContentStream("prompt").collect { chunk ->
    val text = chunk.candidates[0].text
}
// non-streaming: model.generateContent("prompt")
```

## How this maps to Arxiver (`:core:ai`)
- THREE engines implement `OnDeviceEngine { tier; richness; isReady(); generate(ChatRequest): Flow<ChatChunk> }`:
  `GemmaEngine` + `QwenEngine` (LiteRT-LM) and `NanoEngine` (ML Kit GenAI).
- `OnDeviceProvider` (AiProvider, `ProviderId.ON_DEVICE`, `requiresKey=false`, `onDevice=true`)
  picks among ready engines in DI order **Gemma → Qwen (LIGHT) → Nano**, with the user's
  `preferredTier` override. Readiness for provider resolution is `OnDeviceProvider.isReady()`
  (any wired engine ready) — never a hand-enumerated engine seam (the 2026-07-03 hotfix).
- `DeviceCapabilityProbe` reads total RAM (`ActivityManager.MemoryInfo.totalMem`) + Nano
  `checkStatus()` + the Gemma and Qwen-light `ModelState`s; `TierSelector` recommends
  Gemma → LIGHT → Nano → cloud → none.
- Privacy: on-device tiers make **no network calls**. The only allowed download host is
  huggingface.co, carrying TWO pinned model URLs (Gemma 4 + Qwen3-0.6B).

## Sources
- LiteRT-LM Android: https://developers.google.com/edge/litert-lm/android
- LiteRT-LM Kotlin getting started: https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md
- Gemma 4 on LiteRT-LM: https://developers.google.com/edge/litert-lm/models/gemma-4
- Gemma 4 E2B model card: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
- Gemma 4 announcement (Apache-2.0): https://blog.google/innovation-and-ai/technology/developers-tools/gemma-4/
- ML Kit GenAI overview: https://developers.google.com/ml-kit/genai
- ML Kit Prompt API get-started: https://developers.google.com/ml-kit/genai/prompt/android/get-started
- ML Kit GenAI announcement: https://android-developers.googleblog.com/2025/05/on-device-gen-ai-apis-ml-kit-gemini-nano.html
