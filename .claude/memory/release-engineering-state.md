---
name: release-engineering-state
description: v1.0.0 shipped via tag-triggered signed release; signing credentials held offline; gh-release@v3 unexercised
type: project
---

As of 2026-06-11:

- v1.0.0 is published with a signed APK: https://github.com/blokzdev/Arxiver/releases/tag/v1.0.0. Any `v*` tag triggers `.github/workflows/release.yml` → signed build → GitHub Release.
- Signing reads exclusively from Actions secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS` (RSA 2048, PKCS12, 10000-day validity). All signing credentials are held offline by the maintainer — never in the repo; don't ask for them or try to locate them.
- `softprops/action-gh-release@v3` (bumped with the other Node 24 pins in PR #2) has not yet executed — it only fires on a `v*` tag. Sanity-check the publish step on the next release.

Related: [[repo-landing-process]], [[gh-secret-crlf-gotcha]]
