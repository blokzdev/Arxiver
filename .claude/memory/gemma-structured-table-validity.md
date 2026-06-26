---
name: gemma-structured-table-validity
description: PA.0a finding — Gemma E2B emits valid grounded Markdown tables with the PA.2 STRUCTURED exemplar nudge (no LaTeX/Mermaid)
type: gotcha
---

PA.0a, measured on-device 2026-06-26 (emulator API-34 x86_64, the standard 2.59 GB `gemma-4-E2B-it.litertlm`). With the PA.2 `OutputRichness.STRUCTURED` system addendum (a table-focused nudge **with a 1-shot `| Aspect | A | B |` exemplar**, and "no LaTeX/diagrams"), **Gemma E2B reliably emits a valid, correctly-rendered Markdown table** for a comparison question — and does so even in **Standard** mode (the addendum, not Max, drives it). Confirmed on the "Prompt Injection — Single/Multi-Injection" paper: a "compare the two settings" question produced a clean `| Setting | Observation |` 2×2 table rendered as a real bordered table (not raw pipes), each row citing `[1]`.

Two findings that shape PA.3/PA.4:
- **The nudge does not override grounding.** Gemma cited `[1]`, explicitly said the abstract "does not provide a direct comparison," then tabled only the per-setting observations it could legitimately synthesize — no fabricated table. So the table nudge is safe to keep on by default; it won't invent structure from absent data.
- **No LaTeX/Mermaid appeared** — the STRUCTURED "no math/diagrams" constraint held at 2B, matching the research (Mermaid ~60% valid, LaTeX ~9.7% error at this size). Those stay cloud-only (FULL); the valid-by-construction win for on-device structure is grammar/constrained decoding in **PA.4** (gated on PA.0c verifying the LiteRT Kotlin binding exposes it).

The RAG-budget half is a headless CI test (`ChatContextAssemblerTest`): the ~50-token addendum (the assembler's char/4 estimate) drops no chunk at the 4096 on-device window, so it's free at paper scope. Decode of a table answer was ~20–40 s on the emulated x86 CPU (slow but correct; arm64/NPU throughput still wants a flagship). See `VERIFICATION.md` K9. The richness ladder + this exemplar are the seam PA.4 (constrained decoding) and the PA.3 light Qwen tier (`richness=PLAIN`) build on.
