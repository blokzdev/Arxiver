# HUMAN.md — Co-Founder / human-in-the-loop ledger

Arxiver is built by Claude (Opus 4.8, Ultracode mode) running the autonomous loop in [`CLAUDE.md`](CLAUDE.md):
Claude orchestrates planning Workflows, runs a **first-person adversarial validation pass** over every
plan, self-approves subphases within an approved phase, and ships one PR each (green local `./gradlew
build`, green CI, self-merge on green). **The standing blocker is phase-plan approval, not merge.**

This file is the async channel back to the human Co-Founder. It is **not** a status board (`ROADMAP.md`
owns progress) and **not** a fact store (`.claude/memory/` owns durable facts). It holds only what a
human needs to *see or do*. **Skim §2 first — that's what's waiting on you.** To override any §1 decision,
just say so: Claude updates the row, notes the reversal, and threads the change through spec + code in one
commit. Rows are added in the same commit as the work that created them, and resolved (not silently
deleted) when met.

Legend: `OPEN` (waiting on you) · `FYI` (logged for awareness, no action) · `OVERRIDABLE` (Claude decided; reverse anytime).

---

## §1 — Decisions & judgment calls (Claude decided; override anytime)

| Date | Decision | Why | Override |
|---|---|---|---|
| 2026-06-27 | **Personal adversarial-validation step is now part of the loop.** After any planning Workflow, Claude verifies the plan's load-bearing claims against the actual code, attacks the riskiest forks, hunts loose ends, and refines the plan to its strongest in-scope form *before* self-approving. | Agents draft; Claude is the accountable owner. On PH.3 this pass corrected 3 real defects in the Workflow's plan and caught a malformed-fixture flaw before anything shipped. Canonical in `CLAUDE.md` §Operating model. | Tell Claude to trust Workflow output directly, or change what the pass gates on. |
| 2026-06-27 | **P-HTML source chain:** native `arxiv.org/html` → `ar5iv.labs.arxiv.org` → existing PDF viewer (never-strand floor). | Primary-source verified: native covers ~Dec-2023+, ar5iv covers pre-2023 on the same LaTeXML pipeline, PDF already works. `docs/SPEC-P-HTML.md` §2. | Re-rank/drop a source in SPEC-P-HTML §2. |
| 2026-06-27 | **No host-keying on the arXiv rate limiter** — the whole arXiv fetch group (Atom + PDF + HTML) shares one global ≥3s `ArxivRateLimiter`. | Every arXiv host is governed identically; host-keying `acquire()` would break shipped limiter tests for zero red-line gain. SPEC-P-HTML §7. | Ask for per-host spacing. |
| 2026-06-27 | **Semantic Scholar's 1.2s self-spacing mutex is the sole documented exception** — it stays on the bare (un-gated) client. | Different API governance; `api.semanticscholar.org` is already allowlisted if it ever migrates onto the gated client. SPEC-P-HTML §7. | Direct S2 onto the gated client. |
| 2026-06-27 | **`reader.css` is inlined into one `<style>` from a `:core:ai` resource; the reader is a natively-scrolling full-screen page** (no `file://` `<link>`, no `arxiver://height` self-size script). | A `file://` `<link>` can't load under the reader origin's `allowFileAccessFromFileURLs=false`; the self-size script is for inline answer blocks, not a full-screen destination (both were dead code the validation pass removed). SPEC-P-HTML §9. | N/A unless the reader stops being full-screen. |
| 2026-06-27 | **`core/ai/src/test/resources/phtml/` fixtures are trimmed, well-formed excerpts of real arXiv HTML** (`2412.19437`, `2510.04905`, `1706.03762`). | Goldens need real LaTeXML markup. The HTML edition inherits each paper's arXiv license and is **display-only** — Arxiver never re-hosts or exports rendered HTML (SPEC-P-HTML §1/§11). **Licensing FYI:** these are short third-party research-fixture excerpts; if the repo ever goes public, confirm that's acceptable or swap for synthetic. | Flag if the corpus should be synthetic-only or license-annotated. |
| 2026-06-27 | **PH.4 reader build choices:** the ViewModel exposes a **theme-free `ReaderDocument`** and the screen derives the final HTML in `remember(doc, theme)` (light/dark for free, VM stays Compose-free); MVP loads with **`baseUrl = null`** (no sub-resources yet — virtual origin lands in PH.5 with images); **"Read PDF instead" is an always-present toolbar action** (the never-strand floor is one tap from every state). | Smaller, more testable surface than the blueprint's VM↔theme handshake + sandbox-relocation (the adversarial pass dropped both as over-builds). SPEC-P-HTML §9/§11/§12. | Reverse any of these in SPEC-P-HTML. |

**Approved by you during P-HTML planning (2026-06-27), now built:** add `ar5iv.labs.arxiv.org` as a new
network host · strip images in the MVP (figcaption placeholders; pre-fetch → PH.5) · additive "Read HTML"
button (augments, never replaces, the PDF entry) · fix the pre-existing PDF rate-limit bypass in its own PR (PH.2).

---

## §2 — Needs from you (the loop can't do these)

| Status | Item | Detail |
|---|---|---|
| OPEN | **Device-verify the P-HTML reader — PH.4 is now shipped, checks `VERIFICATION.md` §M (M2–M8) are waiting.** A live native ≥2024 paper **and** a pre-2023 ar5iv paper render end-to-end (M2/M3); **airplane-mode** render from cache with **zero egress** (M4); **native MathML** measured with a stress sample (display eq + matrix + stretchy delimiters + scripts) on **Pixel_3a_API_34 AND an API-26/27 old-WebView AVD** (M5) — that verdict decides whether PH.8's bundled MathML fallback is needed; fallback-to-PDF + back-stack (M6); cross-ref + external-link confirm (M7); TalkBack reads it (M8). CI can't run a device; these never block `[x]` but must be observed before the reader is trusted. | `VERIFICATION.md` §M |
| OPEN | **Provision an API-26/27 AVD with an old System WebView** (the MathML floor test above needs it). The local-emulator memory only covers the API-34 target. | prerequisite for §M MathML verdict |
| OPEN (non-blocking) | **Give a real arXiv User-Agent contact** (URL or email). Requests already send a working `Arxiver/<version>` UA; arXiv etiquette wants a reachable `(+<contact>)` appended. Claude wires whatever you publish. | SPEC-P-HTML §7 |
| FYI | **Releases/tags are maintainer-only** — tag pushes are blocked from cloud sessions; you cut `v*` tags via the GitHub UI (fires the signed-release workflow). Signing secrets are held offline by you; Claude never asks for or locates them. | memory `release-engineering-state`, `gh-secret-crlf-gotcha` |

---

## §3 — Open questions & carve-outs (awaiting a steer; non-blocking)

| Status | Item | Default Claude is taking |
|---|---|---|
| OPEN | **PH.8 bundled MathML fallback — build it?** Gated on the §2 device measurement of native WebView MathML on old WebViews. | Ship native-only; decide after the device verdict. |
| OPEN | **Re-enable R8 shrinking?** Disabled after two obfuscation casualties; ~no benefit for a sideloaded single-user app. | Stays OFF until keep-rules are audited + the signed APK is device-smoked. v2 backlog. |
| FYI | **P-HTML MVP scope cuts** (deliberate, tracked): offline images → PH.5 · TOC/section-jump → PH.6 · find-in-page + selection→Ask → PH.7 · bundled math fallback → PH.8. **Out of phase entirely:** full-text HTML search indexing, bulk corpus prefetch, save/export of rendered HTML. | Elevation, not MVP — raise one to re-prioritize. |

---

## §4 — Setup & environment (pointers; truth lives in `.claude/memory/`)

No separate `SETUP.md` — duplicating env facts would create a drift surface the memory harness forbids.

- **Local build JVM = JDK 17** (matches CI; JDK 21 causes a Windows daemon jar-lock + a test flake). → memory `local-build-jdk17`
- **Windows Gradle jar-lock recovery** (`classes.jar … used by another process`): `./gradlew --stop` + force-remove the locked intermediate. → memory `windows-gradle-jar-lock`
- **How changes land:** `main` is protected, PR-only; CI's required check runs on `pull_request` — open a (draft) PR early. → memory `repo-landing-process`
- **Emulator / SDK / AVD / JDK-17 paths + reboot procedure**, `Pixel_3a_API_34` `[E]` target → the local `local-emulator-verification-env` memory (the API-26/27 old-WebView AVD in §2 is the one gap).

---

*Maintained by Claude under the `CLAUDE.md` loop. Override any §1 decision by saying so.*
