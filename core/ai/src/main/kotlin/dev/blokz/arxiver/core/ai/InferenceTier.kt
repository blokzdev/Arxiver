package dev.blokz.arxiver.core.ai

/**
 * The inference back-ends Arxiver can route a chat to, best-first
 * (SPEC-AI-PROVIDERS §3):
 * - [NANO] — system Gemini Nano (ML Kit GenAI), zero-cost, no download, flagship-only.
 * - [GEMMA] — downloaded Gemma 4 E2B via LiteRT-LM, zero-cost, offline, needs RAM (≥4 GB) + a download.
 * - [LIGHT] — a downloaded light model (Qwen3-0.6B) via LiteRT-LM (P-Atlas PA.3): the **floor of capable
 *   on-device** for the ~2–4 GB device segment that can't run Gemma. Less capable than Gemma (so it ranks
 *   below it) but more capable than Nano (no ~256-token output cap); `richness = PLAIN`.
 * - [CLOUD] — BYOK cloud (Claude/Gemini), device-independent, best quality.
 * - [NONE] — nothing usable yet (no key, no model, unsupported device).
 *
 * Persisted by **name** (`AiProviderStore`), never by ordinal — so the declaration order is free.
 */
enum class InferenceTier { NANO, GEMMA, LIGHT, CLOUD, NONE }
