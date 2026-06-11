# Memory index

> One file per fact beside this index; protocol in CLAUDE.md → Memory harness. Keep this index to one line per memory.

- [Repo landing process](repo-landing-process.md) — protected main; CI only on pull_request → open a draft PR early
- [Release engineering state](release-engineering-state.md) — v1.0.0 shipped; CI signs from Actions secrets; gh-release@v3 unexercised until next tag
- [gh secret CRLF gotcha](gh-secret-crlf-gotcha.md) — set secrets byte-exact from Windows; a stray \r breaks base64 -d on runners
- [Claude Routines UI contract](claude-routines-ui-contract.md) — observed routine-creation fields/triggers; connectors run without permission prompts
