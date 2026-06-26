---
name: qwen-light-tier-modelspec
description: The PA.3 light-tier model to pin — litert-community/Qwen3-0.6B → Qwen3-0.6B.litertlm (614 MB CPU build); avoid the .mediatek NPU sibling
type: reference
---

PA.0b finding (2026-06-26, file list verified directly via the HF tree API). The PA.3 light on-device tier pins **`litert-community/Qwen3-0.6B`** (HuggingFace, `library: litert-lm`, Apache-2.0, base Qwen/Qwen3-0.6B, ~70K downloads). The repo holds three `.litertlm` files — **pin the right one by exact filename + SHA, never the repo loosely:**

- **`Qwen3-0.6B.litertlm` — 614 MB, INT8 — THE CPU BUILD TO PIN.** Community-benchmarked decoding on the CPU backend (~212 prefill / 13 decode tok/s on a Samsung device); not a `-web`/GPU-only artifact.
- `Qwen3-0.6B.mediatek.mt6993.litertlm` — 1.2 GB — **NPU-only (MediaTek MT6993). The F2-trap analog — do NOT pin** (would strand devices without that NPU, like the old `-web` Gemma emitted zero tokens on CPU).
- `qwen3_0_6b_mixed_int4.litertlm` — 498 MB, INT4 — smaller fallback if 614 MB download/RAM is too heavy (some quality trade).

Further fallbacks if Qwen3-0.6B disappoints: `litert-community/Qwen2.5-0.5B-Instruct`, `litert-community/SmolLM2-360M-Instruct`, `gemma-3-270m-it`. The SHA-256 is computed + pinned at PA.3 download time (mirroring `GemmaEngine.SPEC`); `GemmaEngine.isReady()` is pure file-presence so `QwenEngine` clones it. The light tier is `richness=PLAIN` and aimed at the **2–4 GB device segment** (below `GEMMA_RAM_FLOOR_MB=4096`), not Gemma-capable devices. See [[litert-lm-kotlin-no-constrained-decoding]].
