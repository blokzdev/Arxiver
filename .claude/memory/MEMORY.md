# Memory index

> One file per fact beside this index; protocol in CLAUDE.md → Memory harness. Keep this index to one line per memory.

- [Repo landing process](repo-landing-process.md) — protected main; CI only on pull_request → open a draft PR early
- [Release engineering state](release-engineering-state.md) — v1.1.0 shipped; R8 compat mode mandatory (full mode broke Hilt); tags cut from GitHub UI, not cloud sessions
- [gh secret CRLF gotcha](gh-secret-crlf-gotcha.md) — set secrets byte-exact from Windows; a stray \r breaks base64 -d on runners
- [Claude Routines UI contract](claude-routines-ui-contract.md) — observed routine-creation fields/triggers; connectors run without permission prompts
- [Self-improvement routine state](self-improvement-routine-state.md) — live routine fires research payloads at cloud sessions; ledger of shipped augmentations
- [Lint unused-resources gate](lint-unused-resources-gate.md) — `:app:lintDebug` fails on unused strings; add a strings.xml key only with its consumer
