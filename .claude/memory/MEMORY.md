# Memory index

> One file per fact beside this index; protocol in CLAUDE.md → Memory harness. Keep this index to one line per memory.

- [Repo landing process](repo-landing-process.md) — protected main; CI only on pull_request → open a draft PR early
- [Release engineering state](release-engineering-state.md) — v1.1.0 shipped; R8 compat mode mandatory (full mode broke Hilt); tags cut from GitHub UI, not cloud sessions
- [gh secret CRLF gotcha](gh-secret-crlf-gotcha.md) — set secrets byte-exact from Windows; a stray \r breaks base64 -d on runners
- [Claude Routines UI contract](claude-routines-ui-contract.md) — observed routine-creation fields/triggers; connectors run without permission prompts
- [Self-improvement routine state](self-improvement-routine-state.md) — live routine fires research payloads at cloud sessions; ledger of shipped augmentations
- [Lint unused-resources gate](lint-unused-resources-gate.md) — `:app:lintDebug` fails on unused strings; add a strings.xml key only with its consumer
- [Local build JDK 17](local-build-jdk17.md) — build locally on JDK 17 (CI's JVM); JDK 21 causes a Windows daemon file-lock + a coroutine-test flake
- [Robolectric+Room sync executors](robolectric-room-sync-executors.md) — flaky `Illegal connection pointer` / off-thread assertion races → build the in-memory DB with `setQueryExecutor{it.run()}`+`setTransactionExecutor{it.run()}`
- [Gemma STRUCTURED table validity](gemma-structured-table-validity.md) — PA.0a: Gemma E2B emits valid grounded Markdown tables with the PA.2 exemplar nudge; no LaTeX/Mermaid
- [LiteRT-LM Kotlin lacks constrained decoding](litert-lm-kotlin-no-constrained-decoding.md) — PA.0c: #1662 open; PA.4 pivoted to "app-draws-the-structure" (constrained decoding is the wrong tool at our scale)
- [Qwen light-tier ModelSpec](qwen-light-tier-modelspec.md) — shipped in QwenEngine.SPEC: litert-community/Qwen3-0.6B → Qwen3-0.6B.litertlm (614 MB CPU); avoid the .mediatek NPU sibling; readiness via OnDeviceProvider.isReady()
- [Readiness consumer-sweep rule](readiness-consumer-sweep.md) — PA.6 incident: when an abstraction's summary grows, sweep the summary's CONSUMERS; device-verify additive tiers ALONE, not just coexisting
