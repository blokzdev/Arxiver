# Arxiver — Product Requirements Document

**Version:** 1.0 · **Status:** Approved · **Platform:** Android (Kotlin + Jetpack Compose)

## 1. Vision

Researchers drown in arXiv volume and juggle disconnected tools: an RSS reader for new papers, a browser for search, a PDF app for reading, a notes app for thinking, and a chat assistant for digestion — none of which share state. Arxiver unifies discovery, indexing, reading, and digestion into one local-first Android app, and delegates open-ended intelligence to the user's own Claude via the Routines API trigger.

**One-liner:** *Your personal arXiv engine, in your pocket, wired to your Claude.*

## 2. Personas

| Persona | Description | Primary jobs |
|---|---|---|
| **The Active Researcher** | PhD student / industry scientist tracking 2–4 subfields daily | Skim daily firehose fast; save and organize; find "papers like this"; weekly digest of what matters |
| **The Practitioner** | ML engineer who reads selectively for work | Targeted semantic search ("KV-cache compression methods"); deep-dive digests of single papers; compare approaches |
| **The Curious Generalist** | Technically literate reader exploring beyond their field | Browse categories; plain-language digests via Claude; build a small personal library |

All personas are power users comfortable sideloading an APK and pasting an API token. v1 optimizes for them.

## 3. Jobs-to-be-done

1. **Triage:** "Show me what's new in my fields today, ranked so I can clear it in 5 minutes."
2. **Find:** "Find papers about X — by meaning, not just keywords — in my library and on arXiv."
3. **Keep:** "Save this paper with tags/notes into collections; have it fully indexed and available offline."
4. **Connect:** "Show me what this paper cites, what cites it, and what's related in my library."
5. **Digest:** "Send these papers to my Claude routine: digest, compare, deep-dive, or fold into my weekly review."

## 4. Feature requirements by phase

Phases match `ROADMAP.md`. Every phase ships an installable, usable APK.

### Phase 1 — Browse & Read (MVP reader)
- **F1.1** Browse arXiv by category: latest listings via arXiv API/RSS, full category taxonomy built in.
- **F1.2** Online search of all of arXiv: query builder supporting `ti:` `au:` `abs:` `cat:` field prefixes, boolean AND/OR/ANDNOT, date ranges; respects 3s rate limit transparently (queueing UI, never an error toast).
- **F1.3** Paper detail screen: title, authors, abstract (LaTeX-aware rendering best-effort), categories, dates, versions, comments, DOI/journal ref, links.
- **F1.4** PDF download and in-app viewing; downloaded PDFs cached on device.
- **F1.5** Share-in support: open `arxiv.org/abs/...` links shared from other apps directly to the detail screen.
- **F1.6** Metadata of every viewed paper cached locally (offline revisit).

### Phase 2 — Library & Index
- **F2.1** Personal library: save/unsave papers; reading status (to-read / reading / read); star rating.
- **F2.2** Collections (manual folders) and tags (flat, multi-assign).
- **F2.3** Notes per paper: markdown text, timestamps, editable.
- **F2.4** Full-text keyword search (FTS5) across library titles, abstracts, authors, notes — instant and offline.
- **F2.5** Follows: categories, authors, and saved queries. Background sync (WorkManager) pulls new matching papers into an **Inbox** feed; user triages (save / dismiss).
- **F2.6** Library export: JSON + BibTeX.

### Phase 3 — Semantic Engine
- **F3.1** On-device embedding pipeline: model downloaded on first use (~25MB, with progress UI); all library + inbox papers embedded in background.
- **F3.2** Hybrid local search: keyword (FTS5) + semantic (sqlite-vec) fusion — see SPEC-SEARCH.
- **F3.3** Related papers: per library paper, pre-computed top-K semantic neighbors from the local corpus.
- **F3.4** Citation graph: per library paper, fetch references/citations from Semantic Scholar (free key, nightly batch); store as edges; show "cites / cited by / co-author" connections, with in-library links highlighted.
- **F3.5** Semantic triage: inbox ranked by similarity to the user's library (their revealed interests).

### Phase 4 — Claude Bridge
- **F4.1** Routine configuration: user adds one or more routines (name + trigger URL + token). Token stored encrypted (Keystore-backed); never displayed back, never logged, never exported.
- **F4.2** Action catalog dispatched as structured POST payloads: **Digest** (1–N papers), **Deep-dive** (single paper), **Compare** (2–6 papers), **Weekly review** (recent library/inbox activity), **Literature scan** (a question + relevant local context), **Custom** (free-form instruction + selected papers).
- **F4.3** Payload includes paper metadata, abstracts, arXiv/PDF links, user notes and tags (opt-in toggle), and the instruction — see SPEC-CLAUDE-BRIDGE for schema.
- **F4.4** Dispatch history with status (sent / failed / HTTP code), payload preview, and retry.
- **F4.5** Graceful failure: offline queueing of dispatches; clear errors for revoked tokens.

### Phase 5 — Polish & Release
- **F5.1** Onboarding flow (pick categories to follow; optional Claude routine setup; optional model download).
- **F5.2** Settings: sync cadence, storage management (PDF cache size, clear), embedding model management, theme.
- **F5.3** Full backup/restore: single-file export (DB + settings, excluding tokens) and import.
- **F5.4** Performance hardening: cold start < 2s, search < 300ms on 5K-paper library, list scrolling 60fps.
- **F5.5** Signed release builds via CI; v1.0.0 GitHub Release with APK.

## 5. Non-goals (v1)

- **No iOS, no tablet-optimized layouts** (phone-first; tablets get the phone layout).
- **No in-app LLM calls** (no Anthropic API key in the app; the Claude bridge is fire-to-routine only). In-app chat-with-paper is a candidate for v2.
- **No full arXiv mirror, no full-text PDF indexing** (index = metadata + abstract + notes; full-text search of PDF bodies is v2 candidate).
- **No accounts, no cloud sync, no telemetry/analytics.**
- **No social features** (comments, sharing networks, public profiles).
- **No response round-trip from Claude routines** — routine output lives in the Claude app. (A webhook/inbox for routine results is the top v2 candidate.)
- **No Play Store release** in v1.

## 6. Constraints & dependencies

- arXiv API: 3s minimum request spacing, 2000 results/request max, daily metadata refresh at midnight UTC. The app must be a respectful client (single request queue, exponential backoff, descriptive User-Agent).
- Semantic Scholar API: free tier ≈1 req/s with key; citation sync must batch and tolerate absence of a key (feature degrades, app doesn't).
- Claude Routines trigger: simple authenticated POST; contract details in SPEC-CLAUDE-BRIDGE. The app must treat the endpoint as opaque and user-owned.
- Device floor: Android 8.0 (API 26), 3GB RAM, ~200MB free storage for a comfortable library.

## 7. Success criteria (v1)

1. A new user can install the APK, follow 2 categories, and triage today's papers within 3 minutes of first launch.
2. Hybrid search over a 1,000-paper library returns relevant results in < 300ms, fully offline.
3. A configured Claude routine receives a well-formed digest payload and produces a useful run with no app-side errors.
4. Zero crashes in normal flows; CI green on every release commit.
