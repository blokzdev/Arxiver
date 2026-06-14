---
name: release-engineering-state
description: v1.1.0/v1.1.1 shipped, v1.1.2 hotfix pending; tag pushes blocked from cloud sessions; release minification DISABLED after two R8 obfuscation casualties
type: project
---

As of 2026-06-14:

- v1.1.0 and v1.1.1 are published with signed APKs (https://github.com/blokzdev/Arxiver/releases); `softprops/action-gh-release@v3` verified working. v1.0.0 also remains published. Any `v*` tag triggers `.github/workflows/release.yml` → signed build → GitHub Release. v1.1.2 is staged on the session branch pending the maintainer cutting the tag.
- Signing reads exclusively from Actions secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS` (RSA 2048, PKCS12, 10000-day validity). All signing credentials are held offline by the maintainer — never in the repo; don't ask for them or try to locate them.
- **Tag pushes are blocked from cloud-session git proxies** (only the session branch is writable; `git push origin <tag>` silently no-ops or hangs up) and the GitHub MCP has no tag/release creation tools. Releases are cut by the maintainer via GitHub UI: Releases → Draft a new release → create tag `v<x.y.z>` on main → publish; the tag push then fires the workflow which attaches the APK to that same release.
- **Release minification is DISABLED** (`isMinifyEnabled = false` + `isShrinkResources = false` in `app/build.gradle.kts`). Two R8 obfuscation casualties hit signed builds we couldn't device-test in CI: (1) v1.1.0 — full mode stripped Hilt multibinding modules (TodayViewModel's `HiltModules$BindsModule`, all `@HiltWorker` factories); compat mode + keep rules patched that, but (2) v1.1.1 — Preferences DataStore's bundled protobuf-lite resolves its generated fields by name via reflection, and compat-mode *renaming* still broke it (`Field value_ for q1.e not found`, crashing the onboarding `setOnboarded()` write; caught by the crash reporter). Rather than chase casualty #3, shrinking was turned off in v1.1.2 — it buys ~nothing for a single-user sideloaded app. **Don't re-enable `isMinifyEnabled` without auditing keep rules for every reflection lib (Hilt, DataStore/protobuf, Room, Retrofit, ONNX) AND device-smoking the signed APK** (v2 backlog task). The `android.enableR8.fullMode=false` flag and the proguard keep rules (Hilt + DataStore/protobuf) remain in place but are inert while minify is off — no `mapping.txt`/`usage.txt` is produced. The old grep audit (`usage.txt` has no `HiltModules`/`_AssistedFactory`) only applies if shrinking is ever switched back on.
- The in-app local crash reporter (v1.1.1, `app/.../CrashReporter.kt`) shows the previous run's trace with a copy button — first ask for that when a device crash is reported. From v1.1.2 on, with minification off, those traces are **de-obfuscated** (real class/field names), so they read directly with no `mapping.txt` needed.

Related: [[repo-landing-process]], [[gh-secret-crlf-gotcha]]
