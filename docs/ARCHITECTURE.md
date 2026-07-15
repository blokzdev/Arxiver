# Arxiver — Architecture

**Status:** Living · Companion specs: SPEC-DATA, SPEC-SEARCH, SPEC-P-SOURCES, SPEC-P-FEEDS, SPEC-P-HTML, SPEC-AI-PROVIDERS, SPEC-P-TOOLS, SPEC-CLAUDE-BRIDGE, SPEC-UI

## 1. Overview

Arxiver is a single-process, local-first Android app. All durable state lives in one SQLite database (plus files for PDFs and downloaded models). Network access is egress-gated to a fixed allowlist — ~10 upstream hosts across the arXiv group, bioRxiv/medRxiv, OpenAlex, Semantic Scholar, and the pinned HuggingFace model host — plus user-configured routine endpoints and, **only ever with a user-supplied key**, the BYOK AI provider APIs. Everything is called directly from the device:

```
┌─────────────────────────────── Device ───────────────────────────────┐
│  Jetpack Compose UI (single activity, Navigation-Compose)             │
│        │ ViewModels (Hilt-injected, StateFlow)                        │
│        ▼                                                              │
│  Domain layer: repositories + use-cases                               │
│   PaperRepository · LibraryRepository · SearchEngine (hybrid)         │
│   SyncEngine (WorkManager) · ChatEngine + ToolRegistry (AI/RAG)       │
│   ClaudeBridge                                                        │
│        ▼                        ▼                                     │
│  core:database (Room/SQLite)   core:network (Retrofit/OkHttp)         │
│   • relational tables           • ArxivApiClient (Atom, ≥3s throttle) │
│   • FTS4 virtual tables         • BioRxivApiClient · OpenAlexClient   │
│   • embedding BLOB table        • SemanticScholarClient               │
│     (chunked cosine scan)       • RoutineTriggerClient · PdfDownloader │
│   • graph edge tables                                                 │
│        ▲                                                              │
│  core:ml — ONNX: tokenizer + embedding model (bge, downloaded)        │
│  core:ai — on-device LLMs (Qwen3/Gemma, LiteRT) + BYOK adapters       │
│            (Anthropic / Gemini — user key only)                       │
└───────────────────────────────────────────────────────────────────────┘

Egress (allowlist): arXiv group (export.arxiv.org, arxiv.org, ar5iv.labs.arxiv.org) ·
bioRxiv/medRxiv (api.biorxiv.org, www.biorxiv.org, www.medrxiv.org) · OpenAlex (api.openalex.org) ·
chemRxiv (chemrxiv.org) · Semantic Scholar (api.semanticscholar.org) · HuggingFace (huggingface.co, pinned models) ·
user routine trigger (POST) · BYOK Anthropic/Gemini (user key only).
```

## 2. Module map

Gradle modules, dependency direction strictly downward:

| Module | Contents | Depends on |
|---|---|---|
| `:app` | Activity, navigation, DI wiring, all `feature.*` UI packages (explore, library, paper, reader (HTML/PDF), chat, claude, settings, onboarding) | all core modules |
| `:core:model` | Pure Kotlin data classes (Paper, Author, Note, RoutineAction…), no Android deps | — |
| `:core:common` | Result types, dispatchers, time, logging | `:core:model` |
| `:core:database` | Room database, DAOs, FTS4 external-content tables, embedding BLOB tables (paper + chunk), graph edge tables, migrations | `:core:model` |
| `:core:network` | arXiv Atom client + parser, bioRxiv/medRxiv client, OpenAlex client, Semantic Scholar client, `PreprintSourceRegistry`, routine trigger client, PDF downloader (multi-host), per-host rate limiters | `:core:model` |
| `:core:ml` | ONNX Runtime session, WordPiece tokenizer, embedding-model download manager | `:core:model` |
| `:core:ai` | On-device LLM engines (Gemma, Qwen3/Nano via LiteRT), inference-tier selection + device-capability probe, BYOK provider adapters (Anthropic, Gemini), reader/RAG document writer | `:core:model`, `:core:database` |
| `:core:search` | Hybrid search engine (keyword + semantic + full-text body), score fusion, related-papers computation | `:core:database`, `:core:ml` |
| `:core:claude` | Payload builders, dispatch queue, token vault (EncryptedSharedPreferences) | `:core:database`, `:core:network` |

Features live as packages inside `:app` (not separate modules) — deliberate: this is a small-team/agent-built codebase and per-feature modules would tax build time and ceremony without payoff at this scale. Core modules carry the real boundaries.

## 3. Key components

### 3.1 ArxivApiClient (`:core:network`)
- Retrofit + OkHttp against `https://export.arxiv.org/api/query`; responses are Atom 1.0 XML, parsed with a hand-rolled XmlPullParser (no heavyweight XML libs).
- **Global rate limiter:** a single `Mutex`-guarded queue ensures ≥3s spacing between *all* arXiv requests app-wide (search, sync, detail refresh share one lane). Callers suspend; UI shows queued state.
- Query builder maps structured filters → arXiv query syntax (`cat:cs.LG AND abs:"state space"`); paging via `start`/`max_results`.
- Descriptive `User-Agent: Arxiver/<version> (https://github.com/blokzdev/arxiver)`.

### 3.2 Database (`:core:database`) — one SQLite file, four roles
- **Relational:** Room entities/DAOs (papers, authors, library, collections, tags, notes, follows, dispatches). Schema in SPEC-DATA.
- **Keyword:** `papers_fts` FTS4 external-content table (title, abstract, author names, notes) maintained via Room — FTS4, **not** FTS5 (FTS5 is unavailable on the target/Robolectric SQLite; see SPEC-DATA §3).
- **Vector:** embeddings persist as plain Room BLOB tables (`paper_embeddings`, `chunk_embeddings`; L2-normalized float32). KNN is a chunked cosine scan in Kotlin (`VectorIndex`) — comfortably fast at orbit scale (~10K papers × 384 dims in tens of ms). (`sqlite-vec`'s `vec0` virtual table is a tracked v2 optimization, **not** shipped — the code comment in `EmbeddingEntities.kt` records this.)
- **Graph:** `citation_edges` (paper→paper, source=s2) and co-authorship derived from `paper_authors`. Traversal via recursive CTEs in DAO queries; no graph database.

### 3.3 SearchEngine (`:core:search`)
Hybrid fusion engine, full spec in SPEC-SEARCH. Two legs in parallel coroutines — FTS4 (BM25) and vector (chunked cosine) — normalized, weighted ~25/75, deduped, quality-gated. A separate full-text **body** leg searches the HTML/PDF chunk index and surfaces as an "Also found in full text" section (off the traced path). Online mode proxies to the source clients instead — arXiv natively, other sources via OpenAlex / Semantic Scholar (one source per submit).

### 3.4 EmbeddingService (`:core:ml`)
- Model: `bge-small-en-v1.5-q8` quantized ONNX (384-dim, ~34MB), downloaded on first use from a pinned HuggingFace URL with checksum verification; never bundled in the APK.
- WordPiece tokenizer implemented in Kotlin from the bundled `vocab.txt` (no JNI tokenizer dep).
- Embeds `title + "\n" + abstract`, truncated to 512 tokens. Batch embedding runs in a WorkManager job (charging/idle-friendly constraints), ~5–20 papers/s on mid-range hardware.

### 3.5 SyncEngine (WorkManager)
| Job | Cadence | Work |
|---|---|---|
| `FollowSyncWorker` | periodic (user-set, default 6h) | For each follow (category/author/query): fetch new papers since last sync → upsert → inbox |
| `EmbeddingWorker` | on-demand chain after sync | Embed un-embedded papers; refresh related-papers for affected library entries |
| `CitationSyncWorker` | nightly | Batch Semantic Scholar lookups for library papers (citations, counts); skipped without a key |
| `DispatchWorker` | on-demand w/ network constraint | Drain queued Claude routine dispatches |

All workers are idempotent and resume-safe (cursor columns in DB, see SPEC-DATA).

### 3.6 ClaudeBridge (`:core:claude`) — one of three intelligence tiers
- `TokenVault`: routine tokens in `EncryptedSharedPreferences` (AES-256, Keystore master key). DB stores only a token *alias*. Tokens excluded from backup/export and from `android:allowBackup`.
- `PayloadBuilder`: action + selected papers/notes → versioned JSON (`arxiver/v1`, additive; SPEC-CLAUDE-BRIDGE). Non-arXiv papers ride optional `source`/`native_id`/`url` keys.
- `RoutineTriggerClient`: POST with `Authorization: Bearer <token>`; response recorded to `routine_dispatches`. Offline → enqueue for `DispatchWorker`.

### 3.7 AI stack (`:core:ai` + the chat feature)
The Routines bridge (§3.6) is the *dispatch* tier; the other two intelligence tiers run **inside** the app:
- **On-device LLMs:** `GemmaEngine` (Gemma 4 E2B, ~2.59 GB) and `QwenEngine`/`NanoEngine` (Qwen3-0.6B light tier, ~614 MB) run locally via LiteRT/ONNX; `DeviceCapabilityProbe` + `InferenceTier` pick a tier for the hardware. Models are opt-in downloads (pinned HuggingFace URLs), never bundled.
- **BYOK providers:** `AnthropicProvider` / Gemini adapters call `api.anthropic.com` / `generativelanguage.googleapis.com` **only** with a user-supplied key (stored in `AiKeyVault`, EncryptedSharedPreferences, never exported).
- **Surfaces:** an in-app **chat** tab, per-paper **Ask**, agentic **tool-use** (`ToolRegistry` — search the corpus mid-conversation), and **RAG** over the library/collections (body-chunk retrieval). See SPEC-AI-PROVIDERS, SPEC-P-TOOLS, `docs/P2-PLAN.md`, and `docs/reference/on-device-ai.md`.

## 4. Cross-cutting decisions

| Concern | Decision |
|---|---|
| DI | Hilt |
| Async | Coroutines + Flow end-to-end; no RxJava |
| Serialization | kotlinx.serialization (JSON payloads, export) |
| Images/PDF | `androidx.pdf` viewer if stable for minSdk, else `PdfRenderer`-based pager |
| Config | minSdk 26, targetSdk/compileSdk 35, JDK 17 toolchain, Kotlin 2.x, AGP 8.x, version catalog (`gradle/libs.versions.toml`) |
| Errors | sealed `AppResult<T>` from repositories; no exceptions across layer boundaries |
| Logging | Timber, debug builds only; **never** log tokens or payload bodies |
| Testing | JUnit + Turbine + Robolectric (DB/DAO + parsers + fusion math + payload builder); instrumentation smoke on CI emulator where feasible |
| CI | GitHub Actions: build, unit test, lint on PR/push; APK artifact; release workflow tags → signed APK → GitHub Release |

## 5. Data flow examples

**Daily triage:** `FollowSyncWorker` → ArxivApiClient (queued, 3s-spaced) → upsert papers + inbox rows → `EmbeddingWorker` embeds new arrivals → inbox screen ranks by similarity-to-library → user saves/dismisses → saved papers join library and the related-papers precompute.

**Dispatch to Claude:** user multi-selects 4 papers → action sheet → "Digest" → `PayloadBuilder` assembles JSON (metadata + abstracts + links + notes-if-enabled + instruction) → `RoutineTriggerClient` POSTs to the routine's trigger URL → Claude app runs the routine with the user's connectors → Arxiver records dispatch status. The response is consumed in the Claude app (explicit v1 non-goal to round-trip it).

## 6. Privacy & security posture

- All personal data on-device; no Arxiver servers exist.
- Outbound calls: the arXiv group, bioRxiv/medRxiv, OpenAlex, chemRxiv, Semantic Scholar (IDs only), the pinned HuggingFace model host, the user's own Claude routine endpoint (payloads the user composed), and — only with a user-supplied key — the BYOK AI providers (Anthropic/Gemini). Nothing else; no analytics/telemetry.
- Routine tokens: Keystore-encrypted, alias-referenced, non-exported, non-logged.
- Backup/export files contain library data and notes but never tokens.
