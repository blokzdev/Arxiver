---
name: release-engineering-state
description: v1.1.0 shipped (signed, gh-release@v3 verified); R8 compat mode mandatory; tag pushes blocked from cloud sessions
type: project
---

As of 2026-06-12:

- Latest published release: v1.1.0 (https://github.com/blokzdev/Arxiver/releases/tag/v1.1.0, signed `arxiver-v1.1.0.apk`). v1.1.1 (R8 hotfix) prepared next. Any `v*` tag triggers `.github/workflows/release.yml` → signed build → GitHub Release; `softprops/action-gh-release@v3` verified working on the v1.1.0 run.
- Signing reads exclusively from Actions secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS` (RSA 2048, PKCS12, 10000-day validity). All signing credentials are held offline by the maintainer — never in the repo; don't ask for them or try to locate them.
- **Tag pushes are blocked from cloud-session git proxies** (only the session branch is writable; `git push origin <tag>` reports "Everything up-to-date" without pushing). The user cuts releases from the GitHub UI: Releases → Draft new release → create tag on main → publish; the workflow then attaches the APK to that same release.
- **R8 full mode must stay OFF** (`android.enableR8.fullMode=false` in gradle.properties + Hilt keep rules in app/proguard-rules.pro): full mode strips Hilt multibinding modules — v1.1.0 shipped with TodayViewModel's `HiltModules$BindsModule` gone (crash navigating to Today) and all `@HiltWorker` assisted factories gone (workers uninstantiable). Diagnose release-only issues from `app/build/outputs/mapping/release/{usage,mapping}.txt`; release workflow does NOT archive mapping.txt.
- Sideloaded builds have no crash pipeline; the in-app local-only CrashReporter (trace → filesDir, copy dialog next launch) is the field-debugging channel.

Related: [[repo-landing-process]], [[gh-secret-crlf-gotcha]]
