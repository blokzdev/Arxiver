---
name: release-engineering-state
description: v1.1.0 shipped (v1.1.1 hotfix pending); tag pushes blocked from cloud sessions; R8 full mode forbidden (Hilt stripping)
type: project
---

As of 2026-06-12:

- v1.1.0 is published with a signed APK (https://github.com/blokzdev/Arxiver/releases/tag/v1.1.0); `softprops/action-gh-release@v3` verified working on that run. v1.0.0 also remains published. Any `v*` tag triggers `.github/workflows/release.yml` → signed build → GitHub Release.
- Signing reads exclusively from Actions secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS` (RSA 2048, PKCS12, 10000-day validity). All signing credentials are held offline by the maintainer — never in the repo; don't ask for them or try to locate them.
- **Tag pushes are blocked from cloud-session git proxies** (only the session branch is writable; `git push origin <tag>` silently no-ops or hangs up) and the GitHub MCP has no tag/release creation tools. Releases are cut by the maintainer via GitHub UI: Releases → Draft a new release → create tag `v<x.y.z>` on main → publish; the tag push then fires the workflow which attaches the APK to that same release.
- **Never re-enable R8 full mode.** v1.1.0 shipped broken because full mode (AGP 8 default) strips Hilt multibinding modules: TodayViewModel's `HiltModules$BindsModule` (instant crash on first navigation to Today) and all `@HiltWorker` assisted factories (workers uninstantiable). Fixed by `android.enableR8.fullMode=false` in `gradle.properties` + keep rules in `app/proguard-rules.pro`. After any R8/dependency change, verify `app/build/outputs/mapping/release/usage.txt` contains no `HiltModules`/`_AssistedFactory` entries (`./gradlew :app:assembleRelease`, then grep).
- The release workflow does NOT archive `mapping.txt`; release crash traces are obfuscated. The in-app local crash reporter (v1.1.1) shows the previous run's trace with a copy button — first ask for that when a device crash is reported.

Related: [[repo-landing-process]], [[gh-secret-crlf-gotcha]]
