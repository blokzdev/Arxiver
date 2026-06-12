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

Single activity, Navigation-Compose, bottom navigation bar with 4 destinations + nested routes:

```
BottomNav
├── Today      (inbox/triage feed)
├── Browse     (categories → listings)
├── Search     (local | arXiv tabs)
└── Library    (papers | collections | tags)

Stacked routes (from anywhere):
  paper/{id}            Paper detail
  paper/{id}/pdf        PDF reader
  paper/{id}/graph      Connections (citations/related)
  claude/dispatch       Dispatch confirm sheet (modal)
  claude/history        Dispatch history
  settings/*            Settings, routine management, follows management
  onboarding            First-run flow
```

Deep links: `https://arxiv.org/abs/{id}` and `arxiv.org/pdf/{id}` (share-in + link interception) → `paper/{id}`.

## 3. Screen inventory

### Today (inbox)
- Sections: **Likely relevant** (Phase 3 similarity score) and **More from your follows**; recency sort pre-Phase 3.
- Item: title, authors (truncated), primary category chip, score bar (subtle), arrival time. Swipe right = save to library, swipe left = dismiss, tap = detail.
- Top bar: sync status/last synced, manual refresh (respects rate limiter — shows "queued" if throttled).
- Empty states: no follows yet → CTA to Browse/follow; all triaged → "Inbox zero" moment.

### Browse
- Category groups (Physics, Math, CS, …) → category list with follow toggles → category listing (latest, paged).
- Category listing doubles as preview of "what following this feels like".

### Search
- One field, two result tabs: **Library** (local hybrid) and **arXiv** (online). Filter chips: category, date, library-only, tag, status.
- Local results show provenance badges (keyword/semantic/both) and similarity where applicable.
- arXiv tab shows the rate-limit queue state gracefully ("searching arXiv…" with spacing-aware progress, never raw errors for throttling).
- Recent queries persisted (local, clearable).

### Library
- Tabs: Papers (filter/sort: added, updated, rating, status), Collections (grid), Tags (chips cloud → filtered list).
- Multi-select mode → bulk actions: collect, tag, status, **Send to Claude**, delete.

### Paper detail
- Hero: title, authors (tappable → author papers), category chips, dates/version, citation count (when synced).
- Abstract (expandable), action row: **Save/Saved**, **PDF**, **Send to Claude**, overflow (share, open on arxiv.org, copy BibTeX).
- Sections: Notes (inline add/edit, markdown), Related papers (similarity bars), Connections preview (cites/cited-by counts → graph screen), metadata (DOI, journal ref, comment).

### Connections (graph)
- v1 is a **list-based graph view**, not a force-directed canvas: References / Cited by / Related / Shared-author sections, each row showing in-library badge. (A visual graph is v2; lists are denser and actually navigable on a phone.)

### PDF reader
- Paged PDF view, night-mode invert toggle, page slider, share. Download manager handles fetch with notification for large files.

### Claude — dispatch sheet & history
- Dispatch sheet (modal bottom sheet): routine selector, action selector (contextual: 1 paper → digest/deep-dive/custom; 2–6 → +compare), editable instruction, include-notes switch, collapsible payload preview, Send.
- History screen per SPEC-CLAUDE-BRIDGE §6.
- Routine management (settings): add/edit/delete routines, "copy routine starter instructions" button (paste-ready block for the Claude app explaining the payload schema).

### Settings
- Follows management, sync cadence, embedding model (download/status/re-index), storage (PDF cache usage + clear), backup/export & import, theme, about/licenses.

### Onboarding (Phase 5)
1. Welcome (the pitch in one screen) → 2. Pick categories to follow (chips, searchable) → 3. Optional: semantic search model download → 4. Optional: connect a Claude routine (or "later") → Today screen with first sync running.

## 4. Component conventions

- `PaperListItem` is THE list cell, with slots for: score bar, provenance badge, swipe actions, selection state. One composable, parameterized — Today/Search/Library/Related all use it.
- All screens: ViewModel + immutable `UiState` data class + sealed `UiEvent`. No business logic in composables.
- Loading: content-shaped skeletons for lists; never full-screen spinners after first frame.
- Errors: inline retry affordances; rate-limit states are *informative*, not error-styled.
- Previews: every screen gets `@Preview` light/dark with fixture data (fixtures shared with tests).

## 5. Accessibility & quality bar

- TalkBack labels on all actionables; swipe actions have accessible alternatives (overflow menu).
- Min text contrast AA; respects system font scale to 1.3× without truncation of titles (wrap, don't ellipsize, in detail views).
- Baseline profile generated before release for startup performance (Phase 5).
