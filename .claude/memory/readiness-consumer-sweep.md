---
name: readiness-consumer-sweep
description: Process rule from the PA.6 Qwen-only incident — when a change grows what an abstraction summarizes, sweep the CONSUMERS of the summary; device-verify additive tiers ALONE, not just coexisting
type: process
---

Two process rules bought by the 2026-07-03 PA.6 incident (the Qwen light tier shipped unreachable
on its whole target segment — full post-mortem in the ROADMAP decision log, 2026-07-03):

1. **Consumer sweep.** When a change grows or reshapes what an abstraction summarizes (an engine
   list, a provider set, an enum, a host allowlist), the adversarial pass must sweep the
   *consumers of the summary*, not just the changed class — ask "who else answers this question?"
   (PA.3a added `QwenEngine` to `OnDeviceProvider`'s list and tested the class thoroughly; the
   stale hand-enumerated `onDeviceReady` seam 164 lines away in the same file was never asked.)
   Prefer making the abstraction the *single source* (e.g. `OnDeviceProvider.isReady()`) plus a
   structural test over auditing consumers each time.

2. **Tier-alone device verification.** Device verification of an *additive* capability tier must
   include the **tier-alone configuration** (the new thing as the ONLY provider of its kind), not
   just coexistence — a coexisting sibling can mask a dead resolution path while in-provider
   routing still works (K10 verified Qwen with Gemma resident; the Qwen-only leg is K20).

See [[qwen-light-tier-modelspec]].
