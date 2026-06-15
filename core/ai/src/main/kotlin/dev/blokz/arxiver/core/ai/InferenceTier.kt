package dev.blokz.arxiver.core.ai

/**
 * The inference back-ends Arxiver can route a chat to, best-first
 * (SPEC-AI-PROVIDERS §3):
 * - [NANO] — system Gemini Nano (ML Kit GenAI), zero-cost, no download, flagship-only.
 * - [GEMMA] — downloaded Gemma 4 E2B via LiteRT-LM, zero-cost, offline, needs RAM + a download.
 * - [CLOUD] — BYOK cloud (Claude/Gemini), device-independent, best quality.
 * - [NONE] — nothing usable yet (no key, no model, unsupported device).
 */
enum class InferenceTier { NANO, GEMMA, CLOUD, NONE }
