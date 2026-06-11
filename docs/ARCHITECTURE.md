# Arxiver — Architecture

**Status:** Approved · Companion specs: SPEC-DATA, SPEC-SEARCH, SPEC-CLAUDE-BRIDGE, SPEC-UI

## 1. Overview

Arxiver is a single-process, local-first Android app. All durable state lives in one SQLite database (plus files for PDFs and the embedding model). Network access is limited to four upstream services, all called directly from the device:

```
┌─────────────────────────────── Device ───────────────────────────────┐
│                                                                       │
│  Jetpack Compose UI (single activity, Navigation-Compose)             │
│        │ ViewModels (Hilt-injected, StateFlow)                        │
│        ▼                                                              │
│  Domain layer: repositories + use-cases                               │
│   ├── PaperRepository ──────────┐                                     │
│   ├── LibraryRepository         │                                     │
│   ├── SearchEngine (hybrid)     │                                     │
│   ├── SyncEngine (WorkManager)  │                                     │
│   └── ClaudeBridge              │                                     │
│        ▼                        ▼                                     │
│  core:database (Room/SQLite)   core:network (Retrofit/OkHttp)         │
│   • relational tables           • ArxivApiClient (Atom, 3s throttle)  │
│   • FTS5 virtual tables         • SemanticScholarClient               │
│   • sqlite-vec embeddings       • RoutineTriggerClient (POST)         │
│   • graph edge tables           • PdfDownloader                       │
│        ▲                                                              │
│  core:ml — ONNX Runtime: tokenizer + embedding model (downloaded)     │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
            │                  │                       │
            ▼                  ▼                       ▼
   export.arxiv.org   api.semanticscholar.org   claude.ai routine trigger
   (Atom API + RSS)   (citations, free key)     (user-configured POST)
```

## 2. Module map

Gradle modules, dependency direction strictly downward:

| Module | Contents | Depends on |
|---|---|---|
| `:app` | Activity, navigation, DI wiring, all `feature.*` UI packages (browse, search, library, paper, claude, settings, onboarding) | all core modules |
| `:core:model` | Pure Kotlin data classes (Paper, Author, Note, RoutineAction…), no Android deps | — |
| `:core:common` | Result types, dispatchers, time, logging | `:core:model` |
| `:core:database` | Room database, DAOs, FTS5 + sqlite-vec setup, migrations | `:core:model` |
| `:core:network` | arXiv Atom client + parser, Semantic Scholar client, routine trigger client, PDF downloader, rate limiter | `:core:model` |
| `:core:ml` | ONNX Runtime session, WordPiece tokenizer, model download manager | `:core:model` |
| `:core:search` | Hybrid search engine, score fusion, related-papers computation | `:core:database`, `:core:ml` |
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
- **Keyword:** `papers_fts` FTS5 table (title, abstract, author names, notes) kept in sync via SQL triggers.
- **Vector:** sqlite-vec `vec0` virtual table for embeddings. Requires a bundled SQLite (extension loading is unavailable on the platform SQLite), so the app ships `androidx.sqlite` bundled driver + sqlite-vec native lib. **Fallback (pre-approved):** if extension integration stalls a phase, embeddings persist as BLOB columns and search brute-forces cosine over the library in chunks — fine to ~10K papers — behind the same `VectorStore` interface, so the swap is invisible to callers.
- **Graph:** `citation_edges` (paper→paper, source=s2) and co-authorship derived from `paper_authors`. Traversal via recursive CTEs in DAO queries; no graph database.

### 3.3 SearchEngine (`:core:search`)
Hybrid fusion engine, full spec in SPEC-SEARCH. Two legs in parallel coroutines — FTS5 (BM25) and vector (cosine) — normalized, weighted ~25/75, deduped, quality-gated. Online mode proxies to ArxivApiClient instead.

### 3.4 EmbeddingService (`:core:ml`)
- Model: `bge-small-en-v1.5` quantized ONNX (384-dim, ~25–35MB), downloaded on first use from a pinned HuggingFace URL with checksum verification; never bundled in the APK.
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

### 3.6 ClaudeBridge (`:core:claude`)
- `TokenVault`: routine tokens in `EncryptedSharedPreferences` (AES-256, Keystore master key). DB stores only a token *alias*. Tokens excluded from backup/export and from `android:allowBackup`.
- `PayloadBuilder`: action + selected papers/notes → versioned JSON (SPEC-CLAUDE-BRIDGE).
- `RoutineTriggerClient`: POST with `Authorization: Bearer <token>`; response recorded to `routine_dispatches`. Offline → enqueue for `DispatchWorker`.

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
- Outbound calls: arXiv (queries), Semantic Scholar (paper IDs only), user's own Claude routine endpoint (payloads the user composed). Nothing else.
- Routine tokens: Keystore-encrypted, alias-referenced, non-exported, non-logged.
- Backup/export files contain library data and notes but never tokens.
