# SPEC-UI — Screens, Navigation, Design Language

**Status:** Approved · Implemented in `:app` feature packages

## 1. Design language

- **Material 3**, dynamic color (Monet) with a fallback brand scheme: deep indigo primary `#3F51B5`-family, warm paper-white surfaces; dark theme first-class (researchers read at night). Both fallback schemes define the full surface-container tier (`surfaceDim/Bright`, `surfaceContainerLowest…Highest`) so depth hierarchy survives without dynamic color.
- **Color-role semantics** (consistent across every surface):

  | Role | Meaning | Examples |
  |---|---|---|
  | primary (indigo) | identity, navigation, in-library | section headers, nav selection, bookmark/in-library markers, authors |
  | secondary (amber) | the user's annotation layer | ratings, tags, reading status, category chips |
  | tertiary (teal) | machine-intelligence signals | semantic/keyword provenance badges, similarity & relevance scores, dispatch-sent state |
  | error | destructive + failed only | delete affordances, failed dispatches, auth errors |

  Rate-limit and offline states are informative (`onSurfaceVariant` on plain surfaces), never error-styled.
- Typography: Material 3 type scale; paper titles in a serif-accented style (e.g. `Lora`/platform serif) to evoke print; everything else sans.
- Shapes: gently rounder than M3 defaults (8/12/16/20/28dp) — `extraSmall` chips/badges, `small` inline surfaces/skeletons, `medium` cards, `extraLarge` sheets.
- Density: list-first app; compact list items, generous tap targets (≥48dp), swipe actions where they earn their keep (inbox triage).
- LaTeX in titles/abstracts: best-effort inline rendering of common math via lightweight mapping (subset: super/subscripts, greek, common symbols); raw LaTeX shown as-is when unmapped. No WebView-MathJax in v1 (cost/benefit).
- Motion: standard M3 transitions; no gratuitous animation. Tokens (`ArxiverMotion`): 150ms component state (icon swaps, checkmarks), 250ms in-screen layout (expand/collapse, list moves), 350ms navigation/hero, with M3 standard/decelerate/accelerate easings. Navigation: stacked pushes fade+slide (10% offset), bottom-tab switches fade-through only, pops mirror their push.
- Spacing tokens (`Spacing`): 4/8/12/16/24/32dp; screen interiors at 16dp, empty/hero moments at 32dp.

## 2. Navigation

Single activity, Navigation-Compose, bottom navigation bar with **4 destinations** + nested routes:

```
BottomNav
├── Today      (inbox/triage feed)
├── Explore    (search field, Library|Online scope, category taxonomy as the resting state)
├── Library    (papers | collections | tags)
└── Chat       (resumable conversations: recents + resume + delete/undo)

Stacked routes (from anywhere):
  paper/{id}            Paper detail
  paper/{id}/pdf        PDF reader
  paper/{id}/graph      Connections (citations/related)
  browse/category/{code} Category listing (from the Explore taxonomy)
  chat/session/{id}     Full-screen conversation (PC.1)
  chat/new/{kind}/{id}  Fork a new conversation (PC.1)
  claude/dispatch       Dispatch confirm sheet (modal)
  claude/history        Dispatch history
  settings/*            Settings, routine management, follows management
  onboarding            First-run flow
```
> The tree lists bottom-tab destinations + the principal stacked routes; a few nested routes
> (html, map, library/{mode}) are omitted and a full reconciliation is a pending docs pass.

Deep links: `https://arxiv.org/abs/{id}` and `arxiv.org/pdf/{id}` (share-in + link interception) → `paper/{id}`.


### Explore — the Online scope is multi-source (P-Explorer PE.3)

The scope toggle reads **Library | Online**. Under Online, a **source picker** (leftmost chip in the filter bar →
a bottom-sheet radio list; arXiv default, followed sources ★-sorted, browser-only sources captioned) selects ONE
source per submit: arXiv rides its untouched native Atom path with the full filter sheet; every other source is
one `OpenAlexClient.search` call per explicit submit, optionally narrowed server-side by a curated PE.1 Field chip.
**Metering contract (tested by counting real requests):** keystrokes never touch the network (the local leg is
debounced separately); a source switch cancels in-flight work, clears results, and never auto-searches; external
results are un-paginated v1 (`nextStart = null` → the auto-load-more is structurally inert). Result rows persist
**on interaction** (open/save/dispatch — never on render) through the atomic reuse-or-insert, and every action uses
the returned *winning id* (reuse can re-key a hit onto an existing row). Honesty: the external count header shows
reachable rows (never OpenAlex's full `meta.count`); non-arXiv scopes carry a "via OpenAlex" caption (+ a lag hint
for bio/med, whose native API has no keyword search); abstract-less sources (SSRN ~100%, Research Square ~86% —
licensing strips, permanent) render short cards and an explicit "No abstract available from %s" on detail; a
BROWSER-tier paper's detail screen offers **Open in browser** instead of a doomed in-app PDF button.

## 3. Screen inventory

### Today (inbox)
- **Continue reading (P-Read)** — a calm section at the very top: papers you genuinely opened in a reader, scrolled
  into (past a low progress floor), and haven't finished, most-recently-read first. Each row is a title + a subtle
  **position** line ("34% · HTML edition" — position language, never "percent read"; visual + TalkBack equal in
  strength); tapping resumes at the recorded surface (HTML reader / PDF viewer), which restores its own precise
  position on open. A paper is represented by its **furthest-progress** row across surfaces (an 80%-HTML read is
  never buried under a 3%-PDF glance). **Collapsible-by-absence** (renders only when something honestly qualifies —
  no guilt-CTA), no count badge / notification / streak / completion badge. Finished (sustained dwell) or
  library-`read` papers drop off. This is the honest successor to the cut PA.3 "saved = to-read" shelf.
- Sections: **Likely relevant** (Phase 3 similarity score) and **More from your follows**; recency sort pre-Phase 3.
- Item: title, authors (truncated), primary category chip, score bar (subtle), arrival time. Swipe right = save to library, swipe left = dismiss, tap = detail.
- Top bar: sync status/last synced, manual refresh (respects rate limiter — shows "queued" if throttled).
- Empty states: no follows yet → CTA to Explore's category taxonomy (forces the Library resting state); all triaged → "Inbox zero" moment.

### Explore
- **Search + Browse merged into one discovery surface (PC.2).** One search field over two scopes (segmented toggle): **Library** (local hybrid, live-debounced) and **Online** — a multi-source scope (arXiv natively + bioRxiv/medRxiv/chemRxiv/SSRN/… via OpenAlex + Semantic Scholar, one source per submit; see the PE.3 section below), explicit submit.
- **Resting state (Library scope, blank query): the category taxonomy** — groups (Physics, Math, CS, …) with follow toggles; tapping a category opens its listing (latest, paged) via the stacked `browse/category/{code}` route. This taxonomy is the app's category directory, reached by clearing the query on the Library scope; the Today "no follows" CTA routes here (forcing the Library resting state via a one-shot `?reset=true`).
- Local results show provenance badges (keyword/semantic/both) and similarity where applicable.
- The Online scope shows the rate-limit queue state gracefully ("searching arXiv…"), a source picker, structured filters (category/date/sort) on the arXiv source, and never raw throttle errors.
- The keyboard **Search** action submits on the Online scope (any source — one arXiv/OpenAlex/Semantic Scholar call per submit); the Library scope is live-debounced with no explicit submit.

### Chat

- **Promoted top-level tab (PC.3)** listing resumable chat sessions, most-recently-active first — no longer buried under Settings.
- Each row: a **scope chip** (Paper / Collection); the **label** — the paper title or collection name, resolved in one SQL JOIN (`ChatDao.observeSessionRows()`, replacing the per-row `paperById` N+1), falling back to a generic "removed paper/collection" label when the target was deleted; a **snippet** — the latest non-empty message (assistant answer in the normal case; a Done-with-no-text turn persists `content = ''` and is skipped, so a ghost bubble is never a preview), omitted when the session has no non-empty message yet; and a **relative time**.
- Tapping a row resumes the conversation (`chat/session`). **Delete** hides the row immediately and shows an **Undo** snackbar; the hard delete is committed on the application scope after the undo window, so it survives navigating away from the tab (Undo cancels the pending commit). The empty state offers a **Browse your library** CTA that switches to the Library tab. There is no Settings entry point — the Chat tab is the single entry.

### Library
- Tabs: Papers (filter/sort: added, updated, rating, status), Collections (grid), Tags (chips cloud → filtered list).
- Multi-select mode → bulk actions: collect, tag, status, **Send to Claude**, delete.

### Paper detail
- Hero: title, authors (tappable → author papers), category chips, dates/version, citation count (when synced).
- Abstract (expandable), action row: **Save/Saved**, **PDF**, **Send to Claude**, overflow (share, open on arxiv.org, copy BibTeX).
- Sections: Notes (inline add/edit, markdown), Related papers (similarity bars), Connections preview (cites/cited-by counts → graph screen), metadata (DOI, journal ref, comment).

### Connections (graph)
- v1 is a **list-based graph view**, not a force-directed canvas: References / Cited by / Related / Shared-author sections, each row showing in-library badge. (A visual graph is v2; lists are denser and actually navigable on a phone.)

### HTML reader (primary reading surface)
- The arXiv **HTML edition** (`arxiv.org/html` → `ar5iv.labs.arxiv.org` → PDF fallback) with figures inlined, find-in-page, a TOC sheet, per-paper **Ask**, and the shared tri-state night mode. "Read PDF instead" is always one tap away (never-strand floor). See SPEC-P-HTML.

### PDF reader (universal fallback)
- Paged PDF view; **shared tri-state night mode** (System/Light/Dark, honoured by both readers); **pinch & double-tap zoom with crisp settle tiles** (P-ReaderZoom: mid-gesture the base pages GPU-upscale for instant feedback; ~180ms after the gesture settles, the visible region re-renders at zoom resolution via `PdfRenderer` region-render and a screen-space overlay blits it 1:1 — sharp text at any zoom, memory bounded to ≤ one viewport of tile pixels, disabled on heap-starved hi-DPI devices where the soft upscale is the guarantee); **jump-to-page** (numeric field + −/+ steppers) alongside the page slider; **universal full-text extraction** (pdfbox-android) that feeds full-text body search; a persistent "Continue reading" position on Today; share. Download manager handles fetch with notification for large files.

### Claude — dispatch sheet & history
- Dispatch sheet (modal bottom sheet): routine selector, action selector (contextual: 1 paper → digest/deep-dive/custom; 2–6 → +compare), editable instruction, include-notes switch, collapsible payload preview, Send.
- History screen per SPEC-CLAUDE-BRIDGE §6.
- Routine management (settings): add/edit/delete routines, "copy routine starter instructions" button (paste-ready block for the Claude app explaining the payload schema).

### Settings
- Follows management, sync cadence, embedding model (download/status/re-index), **AI providers** (BYOK Anthropic/Gemini keys) and **on-device chat-model** download/status, **Background activity**, reader theme, storage (PDF cache usage + clear), backup/export & import, theme, about/licenses.

### Onboarding (Phase 5)
1. Welcome (the pitch in one screen) → 2. Pick categories to follow (chips, searchable) → 3. Optional: semantic search model download → 4. Optional: connect a Claude routine (or "later") → Today screen with first sync running.

## 4. Component conventions

- `PaperListItem` is THE list cell, with slots for: score bar, provenance badge, swipe actions, selection state. One composable, parameterized — Today/Search/Library/Related all use it.
- **Selection & swipe (Phase UX2):** multi-select is hoisted ephemeral UI state — `rememberSelectionState()` (a Saveable `SelectionState`, keyed by paper id) plus a shared `SelectionTopBar` (contextual action bar: tonal surface, "N selected", leading ✕, trailing bulk actions). Swipe lives in `SwipeablePaperRow`, a `PaperListItem` wrapper with per-direction opt-in (right = save, left = remove/dismiss) that is **inert while selection mode is on** so the gesture and long-press multi-select coexist. Every list screen composes these rather than re-implementing selection/swipe.
- **Feedback (Phase UX2):** transient feedback is app-level, not per-screen. A single `FeedbackController` (`@Singleton` bus, reachable from any ViewModel or — via `LocalFeedbackController` — composable) feeds one `FeedbackHost` mounted at the app shell `Scaffold`. The host renders one message at a time as an **elevated, dismissible** snackbar (tonal+shadow elevation, rounded `shapes.small`, explicit "✕") with an optional **primary + secondary action** (e.g. *Undo* and *Add to collection*). Custom durations (M3 exposes only Short/Long/Indefinite) come from racing `withTimeoutOrNull` against the user's tap — action-bearing messages linger longer. Screens must not host their own `SnackbarHostState` for routine feedback.
- All screens: ViewModel + immutable `UiState` data class + sealed `UiEvent`. No business logic in composables.
- Loading: content-shaped skeletons for lists; never full-screen spinners after first frame.
- Errors: inline retry affordances; rate-limit states are *informative*, not error-styled.
- Previews: every screen gets `@Preview` light/dark with fixture data (fixtures shared with tests).

## 4a. Background-task status (Phase UX2)

A single `BackgroundTaskMonitor` (`@Singleton`) merges the model-downloaders' `ModelState` and
WorkManager RUNNING state for follow-sync/embedding into one `Flow<List<BackgroundTask>>`. The
`BackgroundTasksSheet` (opened from Settings → *Background activity*, and surfaced when long downloads
run) shows each task with live progress and cancel/retry. Long downloads (the Gemma `.litertlm` and the
bge model) additionally run as a **foreground service with a local progress notification** (UX2.8). All
of this is **local-only** — it observes on-device state and posts an on-device notification; nothing is
sent anywhere (no-telemetry red line). `POST_NOTIFICATIONS` is requested lazily on Android 13+, and a
denial degrades gracefully (the download still runs; only the notification is suppressed).

## 5. Accessibility & quality bar

- TalkBack labels on all actionables; **every** swipe action (all swipeable lists, not just Today) carries a `CustomAccessibilityAction` equivalent built per enabled direction (`SwipeablePaperRow`). Swipe is disabled in selection mode so it never competes with long-press multi-select.
- Min text contrast AA; respects system font scale to 1.3× without truncation of titles (wrap, don't ellipsize, in detail views).
- Baseline profile generated before release for startup performance (Phase 5).
