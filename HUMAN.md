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
| 2026-07-04 | **Removed the Settings -> Chat history link** (PC.3): the promoted Chat tab is now the single entry point to chat history. | Two entries with divergent Back semantics (a stacked Settings screen vs a top-level tab) shouldn't coexist and confuse the back stack. | Want the Settings link back? Re-add a `SettingsLink` navigating to the Chat tab - trivial and reversible. |
| 2026-07-04 | **Explore merge narrows the category-directory discoverability** (PC.2): the taxonomy moved from an always-one-tap Browse tab to Explore's Library-scope blank-query resting state. | The approved merge folds two tabs into one to make room for Chat; the taxonomy has to live inside Explore. Fresh opens + the Today CTA land on it, but a mid-session arXiv-scope user has no signpost back. | If this bites in use, a persistent "categories" affordance (always reachable, incl. from the arXiv scope) is the recorded backlog fix - say the word and it moves up. |
| 2026-07-04 | **The unbuilt "recent queries persisted" Search spec bullet was dropped** (PC.2 SPEC-UI rewrite) rather than carried into Explore. | It was aspirational and never implemented; keeping a dead spec line invites confusion. | Want recent-queries? It becomes a real backlog item on Explore, not a silent spec ghost. |
| 2026-07-04 | **`windowSoftInputMode=adjustResize` set activity-wide** (PC.1). | The new full-screen conversation needs the composer to rise above the keyboard; the M3 modal sheet handled IME implicitly, a Scaffold does not. adjustResize + `imePadding` is the standard Compose pairing. | Affects every screen's IME behavior — if any existing input regresses (VERIFICATION M-PC1-1 checks the known ones), revert to `adjustPan` on that finding and re-scope. |
| 2026-06-27 | **Personal adversarial-validation step is now part of the loop.** After any planning Workflow, Claude verifies the plan's load-bearing claims against the actual code, attacks the riskiest forks, hunts loose ends, and refines the plan to its strongest in-scope form *before* self-approving. | Agents draft; Claude is the accountable owner. On PH.3 this pass corrected 3 real defects in the Workflow's plan and caught a malformed-fixture flaw before anything shipped. Canonical in `CLAUDE.md` §Operating model. | Tell Claude to trust Workflow output directly, or change what the pass gates on. |
| 2026-06-27 | **P-HTML source chain:** native `arxiv.org/html` → `ar5iv.labs.arxiv.org` → existing PDF viewer (never-strand floor). | Primary-source verified: native covers ~Dec-2023+, ar5iv covers pre-2023 on the same LaTeXML pipeline, PDF already works. `docs/SPEC-P-HTML.md` §2. | Re-rank/drop a source in SPEC-P-HTML §2. |
| 2026-06-27 | **No host-keying on the arXiv rate limiter** — the whole arXiv fetch group (Atom + PDF + HTML) shares one global ≥3s `ArxivRateLimiter`. | Every arXiv host is governed identically; host-keying `acquire()` would break shipped limiter tests for zero red-line gain. SPEC-P-HTML §7. | Ask for per-host spacing. |
| 2026-06-27 | **Semantic Scholar's 1.2s self-spacing mutex is the sole documented exception** — it stays on the bare (un-gated) client. **⟶ SUPERSEDED 2026-07-04 (P-Tools PT.3): S2 moves onto the `@ArxivClient` gated client** (the mutex stays; see the P-Tools row below). | Different API governance; `api.semanticscholar.org` is already allowlisted if it ever migrates onto the gated client. SPEC-P-HTML §7. | Direct S2 onto the gated client (now being done in PT.3). |
| 2026-06-27 | **`reader.css` is inlined into one `<style>` from a `:core:ai` resource; the reader is a natively-scrolling full-screen page** (no `file://` `<link>`, no `arxiver://height` self-size script). | A `file://` `<link>` can't load under the reader origin's `allowFileAccessFromFileURLs=false`; the self-size script is for inline answer blocks, not a full-screen destination (both were dead code the validation pass removed). SPEC-P-HTML §9. | N/A unless the reader stops being full-screen. |
| 2026-06-27 | **`core/ai/src/test/resources/phtml/` fixtures are trimmed, well-formed excerpts of real arXiv HTML** (`2412.19437`, `2510.04905`, `1706.03762`). | Goldens need real LaTeXML markup. The HTML edition inherits each paper's arXiv license and is **display-only** — Arxiver never re-hosts or exports rendered HTML (SPEC-P-HTML §1/§11). **Licensing FYI:** these are short third-party research-fixture excerpts; if the repo ever goes public, confirm that's acceptable or swap for synthetic. | Flag if the corpus should be synthetic-only or license-annotated. |
| 2026-07-03 | **Qwen readiness hotfix (PA.6) is structural, not a one-liner.** Your device report ("Qwen downloaded but 'No AI provider set up'") was a stale hand-enumerated readiness seam in DI. Instead of adding `\|\| qwen.isReady()`, the resolver now delegates to `OnDeviceProvider.isReady()` — the same engine list that serves chat — so a future engine can never be forgotten again; a structural test makes a revert a red build. Also shipped in the same PR: a **low-disk crash guard** (first on-device turn on a nearly-full phone previously hard-crashed the app — native SIGABRT — now a graceful storage error) and honest settings copy (the screen claimed on-device "arrives in a later update"). | Kills the bug class, not the instance; the incident post-mortem is in the ROADMAP decision log (2026-07-03). | Ask for the minimal one-liner instead, or reshape the guard's free-space bound. |
| 2026-06-27 | **PH.4 reader build choices:** the ViewModel exposes a **theme-free `ReaderDocument`** and the screen derives the final HTML in `remember(doc, theme)` (light/dark for free, VM stays Compose-free); MVP loads with **`baseUrl = null`**; **"Read PDF instead" is an always-present toolbar action** (the never-strand floor is one tap from every state). | Smaller, more testable surface than the blueprint's VM↔theme handshake + sandbox-relocation (the adversarial pass dropped both as over-builds). SPEC-P-HTML §9/§11/§12. | Reverse any of these in SPEC-P-HTML. |
| 2026-06-27 | **PH.5 figures use `data:`-base64 inline, NOT the SPEC's reserved virtual origin.** Pre-fetched figure bytes are base64-inlined into `index.html`; `baseUrl` stays `null`; both `blockNetworkLoads` and `blockNetworkImage` stay **armed**. | An https virtual origin is suppressed by `blockNetworkImage=true` → would have forced it **off** (disarming an egress control + needing your sign-off); `data:` keeps both blocks armed, adds no dependency, and the validation pass confirmed it. **No red-line escalation was needed** precisely because of this choice. SPEC-P-HTML §8/§12. | Switch to a virtual origin (would re-open the `blockNetworkImage` red-line question). |
| 2026-06-27 | **PH.5 figure caps are PROVISIONAL** (≤40 figures, ≤600 KB each, ≤4 MB total, 30 s deadline) and constructor-injectable. On the ≥3s arXiv limiter the 30 s deadline admits ~8–10 figures on a first online open; the rest stay figcaption placeholders. The shared limiter means a figure-heavy open **bounded-delays** (never starves) a concurrent FollowSync. | Real figure sizes/counts + low-RAM `loadDataWithBaseURL` behaviour are device-facts CI can't measure; these are conservative defaults. | Retune the caps after the §2 device session (no code change — they're DI params). |

| 2026-07-04 | **P-Tools architecture: the tool loop lives ABOVE the provider** (a repo-driven `ChatToolLoop` in `ChatRepository.stream`); providers stay single-shot + gain three additive default-off fields + a `supportsTools` flag. | It's the only layer that owns persistence + the `NonCancellable` finalize + the limiter/AllowedHosts seam + the disclosure surface; a provider-owned loop can't rebuild the in-flight message list after process death and splits disclosure across `:core:ai`↔`:app`. All three judges ranked it highest. SPEC-P-TOOLS §2. | Ask for the loop inside each provider (Stance B) — reopens the process-death + disclosure-split trade-offs. |
| 2026-07-04 | **S2 client moves from bare → the `@ArxivClient` gated client (PT.3)** — reverses the 2026-06-27 row above. | A security tightening: `api.semanticscholar.org` is allowlisted but the interceptor never fires today, so the gate is aspirational. The 1.2s self-spacing mutex stays (S2 is exempt from the ≥3s arXiv limiter). SPEC-P-TOOLS §10. | Keep S2 bare (restores the 2026-06-27 decision). |
| 2026-07-04 | **`chemrxiv.org` added as a new egress host (PT.4)** — the one new host in the phase, **pre-approved by you**. | Cambridge Open Engage's search REST API (chemRxiv); own polite mutex, `@ArxivClient` gated, no off-host CDN redirect on assets. SPEC-P-TOOLS §10. | Drop chemRxiv from P-Tools (keeps the host set at the P-HTML five). |
| 2026-07-04 | **Tool consent is PER-CONVERSATION, not per-call** (an "Enable web search for this chat" toggle) + a persistent inline **activity log** as the real disclosure surface for mid-loop queries; local `search_my_library` is zero-egress and needs no consent. | A per-call gate would be unusable for an agentic loop that makes several calls per turn; the confirm can only show the *initial* request, so the model-minted queries that egress mid-loop are disclosed live in the transcript (query + source + `egress` flag). SPEC-P-TOOLS §9. | Ask for a per-call confirm, or an always-off default. |

**Approved by you during P-HTML planning (2026-06-27), now built:** add `ar5iv.labs.arxiv.org` as a new
network host · strip images in the MVP (figcaption placeholders; pre-fetch → PH.5) · additive "Read HTML"
button (augments, never replaces, the PDF entry) · fix the pre-existing PDF rate-limit bypass in its own PR (PH.2).

**Greenlit by you for P-Tools (2026-07-04), now being built:** full Semantic Scholar (search + details/batch +
recommendations; bioRxiv/medRxiv/PubMed via S2's index) · a robust `chemrxiv.org` host implementation (PT.4) ·
an optional free S2 API key as BYOK (`EncryptedSharedPreferences` via `AiKeyVault`) · plan-approval **delegated**
to Claude ("proceed with planning and implementing maximally") — so P-Tools runs autonomously on the loop.

---

## §2 — Needs from you (the loop can't do these)

| Status | Item | Detail |
|---|---|---|
| OPEN | **Device-verify Phase P-Chat** (all of `VERIFICATION.md` §M-PC1/§N-PC2/§O-PC3/§P-PC5 in one session). Key checks CI can't run: the live **v3→v4 upgrade** (existing chats survive, the 2026-07-04 ghost bubble is gone, pin + rename persist across restart), the **4-tab bar** (Today·Explore·Library·Chat) label fit at 1.3× font scale, IME/rotation on the full-screen conversation, TalkBack on the new actionables, and a **backup export/import round-trip** confirming no chat content leaks. None block the merged code; they confirm the surface on hardware. | `VERIFICATION.md` §M-PC1…§P-PC5 |
| OPEN | **Device-verify the P-HTML reader — PH.4 is now shipped, checks `VERIFICATION.md` §M (M2–M8) are waiting.** A live native ≥2024 paper **and** a pre-2023 ar5iv paper render end-to-end (M2/M3); **airplane-mode** render from cache with **zero egress** (M4); **native MathML** measured with a stress sample (display eq + matrix + stretchy delimiters + scripts) on **Pixel_3a_API_34 AND an API-26/27 old-WebView AVD** (M5) — that verdict decides whether PH.8's bundled MathML fallback is needed; fallback-to-PDF + back-stack (M6); cross-ref + external-link confirm (M7); TalkBack reads it (M8). CI can't run a device; these never block `[x]` but must be observed before the reader is trusted. | `VERIFICATION.md` §M |
| RESOLVED 2026-07-04 | ~~Provision an API-26/27 old-WebView AVD.~~ **Unneeded — PH.8 closed no-go**: the M5 measurement showed native MathML adequate, and System WebView is Play-updated on every API-26+ device, so the frozen-WebView cohort the AVD would have simulated is a museum piece already served by the PDF fallback. Reopens only with evidence of a real frozen-WebView user cohort (ROADMAP PH.8 row). | ROADMAP decision log 2026-07-04 |
| OPEN | **Ratify the PH.5 figure caps on device** (`VERIFICATION.md` M-PH5-4). Open a figure-heavy/survey paper + a near-max single image on the low-RAM API-26 target; confirm `data:`-laden `index.html` renders without truncation and memory is acceptable; record measured size/heap and **retune the caps** (they're DI params — no recompile). Also exercise M-PH5-1…3,5,6 (figures render with both network blocks armed, airplane-mode zero egress, negative-egress + poisoned-cache re-gate, degrade, first-paint/no-starvation). | `VERIFICATION.md` §M (M-PH5-*) |
| RESOLVED 2026-07-04 | ~~Re-verify Qwen-only Ask on your real device.~~ **Confirmed by you: Qwen works on the original device** — K20 closed `[x]` with a dated Verification-log row. | `VERIFICATION.md` K20 |
| OPEN (non-blocking) | **Give a real arXiv User-Agent contact** (URL or email). Requests already send a working `Arxiver/<version>` UA; arXiv etiquette wants a reachable `(+<contact>)` appended. Claude wires whatever you publish. | SPEC-P-HTML §7 |
| FYI | **Releases/tags are maintainer-only** — tag pushes are blocked from cloud sessions; you cut `v*` tags via the GitHub UI (fires the signed-release workflow). Signing secrets are held offline by you; Claude never asks for or locates them. | memory `release-engineering-state`, `gh-secret-crlf-gotcha` |

---

## §3 — Open questions & carve-outs (awaiting a steer; non-blocking)

| Status | Item | Default Claude is taking |
|---|---|---|
| OPEN | **Generic (non-arXiv) import from S2/chemRxiv — carve-out needing your steer.** The `Paper`/library schema is arXiv-id-keyed, so an S2 or chemRxiv result with no arXiv `externalId` can't currently be imported/read in-app. | PT.2/PT.3 import **only** arXiv-resolvable results (search still surfaces the rest as read-only hits). Full generic import needs a schema decision (generic paper id + doi/source) — a future phase, not P-Tools. Say the word if generic import should move up. |
| OPEN (I'll decide at PT.1) | **Pre-turn RAG coexistence with tools.** When tools are enabled for a cloud chat, do we suppress the fixed pre-turn RAG (let the model drive retrieval via `search_my_library`) or keep it and let tools augment? Suppressing collides with the `[n]` citation contract built from the pre-turn chunks. | Resolve against the citation code at PT.1: likely **suppress + re-source citations from tool results** if clean, else **keep RAG + augment**. PT.0 leaves RAG untouched. Non-blocking — flag if you have a preference. |
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
