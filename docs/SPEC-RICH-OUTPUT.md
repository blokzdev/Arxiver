# SPEC-RICH-OUTPUT — Rich AI output & rendering (Phase P-Rich)

Status: **active** · Owner: chat/AI surface · Related: `SPEC-AI-PROVIDERS.md` (chat orchestration), `docs/P2-PLAN.md`, `SPEC-UI.md`.

## 1. Why

Arxiver's AI answers currently render as plain text (`AskBubble` → `Text()`), so the markdown the model already emits (`**bold**`, `## headings`, tables, `[n]` citations) shows as literal characters. Research is concept-dense — methods, comparisons, equations, pipelines, taxonomies — and prose is the weakest medium for most of it. This phase turns AI output into **rich, illustrative, navigable** content so the assistant can explain dense concepts the way papers do, and so chat becomes a first-class surface of the app (not a dead-end text box).

## 2. Principles (non-negotiable)

- **Local + offline rendering.** No network during render. Rich blocks render from **bundled** assets only. (No-telemetry red line.)
- **The key never reaches the renderer.** Rendering operates on the *answer text*; provider keys/library internals are not passed to any WebView or exporter.
- **Sanitize model-emitted markup.** SVG/HTML from the model is sanitized before render; the rich WebView blocks network + file access and exposes no JS↔app data bridge.
- **Provider-aware ambition.** Cloud models (Claude/Gemini) reliably emit structured output; small **on-device** models do not — on-device degrades to plain markdown, and we never promise formats it can't deliver.
- **Graceful degradation.** Any unparseable/unsupported block falls back to its raw fenced source in a code box — never a crash, never a blank.
- **Accessible.** Every rich block carries a text alternative (its source / a caption); the renderer is TalkBack-navigable.

## 3. Emit protocol (what the model produces)

The model answers in **GitHub-Flavored Markdown**. Rich content rides in **fenced code blocks** keyed by info string, plus inline math:

| Block | Syntax | Tier |
|---|---|---|
| Prose, **tables**, lists, code, links | GFM | R0 |
| Citations | `[n]` mapped to retrieved sources | R0 |
| Math | inline `$…$`, display `$$…$$` or ` ```math ` | R1 |
| Diagrams | ` ```mermaid ` | R2 |
| Charts | ` ```chart ` (JSON: type, series, labels) | R2 |
| Vector | ` ```svg ` (sanitized) | R2 |
| arXiv cross-refs | `arXiv:NNNN.NNNNN` in prose | R3 |

**MDX is explicitly rejected:** JSX-in-markdown needs a JS/React runtime and permits arbitrary component execution — wrong for a native, offline, no-telemetry app. Fenced blocks give the same expressive power with a fraction of the dependency and security surface.

## 4. Rendering architecture

Parsing is centralized so every tier shares one AST:

- **Parser:** `org.commonmark:commonmark` + `commonmark-ext-gfm-tables` — a pure-JVM library (no Android/Compose coupling → no version roulette, offline by construction). The same parser Markwon uses; we own the renderer.
- **`MarkdownText` (Compose, R0):** walks the commonmark AST → native Compose. Paragraphs/inlines → `AnnotatedString` (bold, italic, inline code, links, `[n]` citation spans); headings → typography; lists → bullets/ordinals; **GFM tables** → a Compose table; code blocks → a monospace surface; block quotes, thematic breaks. **Fenced code blocks carry their info string**, which is the routing hook: `mermaid`/`math`/`chart`/`svg` info strings dispatch to the rich renderers below; everything else renders as a code box. Remote image loading is **disabled**.
- **`RichBlockWebView` (R1/R2):** a single sandboxed WebView for Mermaid + KaTeX + SVG. Bundled JS/CSS in `assets/` (mermaid.min.js, katex.min.js); `settings.blockNetworkLoads = true`, `blockNetworkImage = true`, `allowFileAccess = false`, `allowContentAccess = false`; JS enabled only to drive the bundled renderer; a fixed CSP; **no `@JavascriptInterface` carrying app data**; the block's source is the only input; rendered height is measured and reported back to Compose for inline layout.
- **Native charts (R2):** ` ```chart ` JSON → a Compose chart (bar/line/scatter), no WebView.

Rendering during **streaming** re-parses the partial text per frame (answers are short); a trailing unterminated fence renders as a plain code box until closed.

## 5. Conversational-quality features

- **Clickable `[n]` citations → source (R0).** The assembler's labeled context (`[i+1] (arXiv:…) …`, `ChatContextAssembler.userTurn`) is the `n → {paperId, excerpt}` map; it is threaded to the assistant message. `[n]` renders as a tappable span; a **"Sources"** expander under the answer lists each excerpt; tapping `[n]` reveals/scrolls to its source. (Render-only — nothing new leaves the device; redaction goldens unchanged.)
- **arXiv cross-reference chips (R3).** `arXiv:NNNN.NNNNN` in prose → a chip that opens that paper *in Arxiver* (reuse the deep-link/share-in resolver from task 1.8). Chat becomes navigation.
- **Pin-to-notes (R3).** Save an answer (or a rendered diagram) into the paper's notes — chat insight becomes durable library content (reuses the notes store).
- **Follow-up suggestion chips (R3)** and optional **length/tone controls** (e.g. "shorter", "for a non-specialist") for engagement.
- **TTS read-aloud (R4-adjacent).** Speak an answer (aligns with the planned P3 `TextToSpeech`), reading the text alternative for rich blocks.

## 6. System prompt (provider-aware)

The chat system instruction (`ChatContextAssembler.SYSTEM_PROMPT`) invites these formats **when they aid understanding**, while preserving grounding + citation rules:

- **Cloud:** full invitation — use markdown tables for comparisons, `mermaid` for pipelines/architectures/sequences, `$…$`/`$$…$$` for math, fenced code for algorithms; still answer ONLY from the provided context and cite `[n]`; if the context lacks the answer, say so.
- **On-device:** minimal invitation — clean markdown only (no diagrams/charts), to avoid unreliable structure from small models.

Changes to `SYSTEM_PROMPT` update the `ChatPreviewBuilder` redaction golden (the preview reflects what's sent; the key stays header-only).

## 7. Export & share (R4)

Render a block (or whole answer) to **PNG/SVG/PDF**; tables → CSV; whole conversation → markdown/PDF. Delivery is a **local file + the Android share sheet** only (no upload, no telemetry). Reuses the existing PDF/share plumbing.

## 8. Build order (R-tiers)

- **R0** — commonmark dep + `MarkdownText` renderer in `AskBubble`; clickable `[n]` citations + Sources expander; markdown system-prompt invitation. *(+ Gemini J3 verification/mapping fix rides along.)*
- **R1** — math: `RichBlockWebView` foundation + KaTeX; math system-prompt invitation.
- **R2** — diagrams/charts/SVG on the WebView + native charts; full system-prompt invitation.
- **R3** — arXiv cross-ref chips, pin-to-notes, follow-up chips, tone controls.
- **R4** — export/share (per-block + whole-conversation), TTS.

## 9. Testing

- `MarkdownText`: AST-render smoke per element (bold/heading/list/**table**/code/link), unterminated-fence fallback, `[n]` span detection — pure/Robolectric, deterministic.
- Citation threading: `ChatContextAssembler` returns the cited chunks in `[n]` order; `AskViewModel` attaches them to the assistant message.
- Redaction goldens (`ChatPreviewBuilderTest`, `PayloadBuilderTest`) stay green (or update with the system-prompt edit).
- Device (`VERIFICATION.md`): an answer with a table + `**bold**` + `[1]` renders formatted (not raw); `[1]` taps to its source; light/dark; TalkBack reads it; rich blocks (R1+) render offline (airplane mode) with no network.
