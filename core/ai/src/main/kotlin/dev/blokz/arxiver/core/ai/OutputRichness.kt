package dev.blokz.arxiver.core.ai

/**
 * The output-richness tier a turn's system prompt invites (P-Atlas PA.2). Resolved from the engine
 * that will actually run, so each model is only asked for output it can emit reliably:
 *
 * - [PLAIN]: the base prompt only (already invites Markdown tables). Tiny models — system Gemini
 *   Nano and the Qwen3-0.6B light tier (PA.3).
 * - [STRUCTURED]: base + a table-focused nudge (with a 1-shot example), **no LaTeX / no Mermaid** —
 *   research puts valid Mermaid at ~60% and LaTeX at ~9.7% error for a ~2B model. Gemma E2B.
 * - [FULL]: base + the cloud rich invitation (LaTeX + Mermaid). Cloud BYOK providers (Claude, Gemini).
 *
 * Valid-by-construction structured output on-device (grammar/constrained decoding) is a later tier
 * (PA.4); this enum is the seam it hooks into.
 */
enum class OutputRichness { PLAIN, STRUCTURED, FULL }
