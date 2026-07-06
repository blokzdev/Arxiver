# SPEC-DATA — Storage Schema & arXiv Mapping

**Status:** Approved · Implemented in `:core:database` (Room) — this document is the source of truth for schema intent; Room entities mirror it.

## 1. Principles

- One SQLite database file: `arxiver.db`. Room manages relational tables and migrations; FTS5 and vec tables are created/maintained via Room callbacks + raw SQL migrations.
- arXiv ID **without version** (`2403.01234` or legacy `math/0211159`) is the canonical paper key; version tracked as a column.
- Every remote-derived row is upsert-safe and carries provenance timestamps; every sync has a cursor so workers are resumable and idempotent.

## 2. Relational tables

### papers
| column | type | notes |
|---|---|---|
| id | TEXT PK | arXiv id, no version |
| latest_version | INTEGER | from `<updated>` entries |
| title | TEXT | LaTeX preserved as-is |
| abstract | TEXT | |
| published_at | INTEGER | epoch ms, v1 date |
| updated_at | INTEGER | latest version date |
| primary_category | TEXT | e.g. `cs.LG` |
| comment | TEXT? | arxiv:comment |
| journal_ref | TEXT? | |
| doi | TEXT? | |
| pdf_url | TEXT | |
| citation_count | INTEGER? | from S2 |
| s2_paper_id | TEXT? | Semantic Scholar id |
| source | TEXT | `search` / `follow` / `share_in` / `manual` |
| fetched_at | INTEGER | local provenance |
| embedded_at | INTEGER? | null = needs embedding |
| citations_synced_at | INTEGER? | null = never synced |

### authors / paper_authors
- `authors(id INTEGER PK, name TEXT, s2_author_id TEXT?)` — name-keyed dedupe (`UNIQUE(name)`); proper disambiguation via S2 ids when available.
- `paper_authors(paper_id, author_id, position)` — PK(paper_id, author_id); position preserves order. Co-authorship graph is derived from this table (self-join), not stored.

### categories / paper_categories
- `categories(code TEXT PK, name TEXT, group_name TEXT)` — full arXiv taxonomy **bundled at build time** as Kotlin source (`ArxivTaxonomy` in `:core:model`) and upserted idempotently on app start (taxonomy is static enough; no endpoint exists).
- `paper_categories(paper_id, category_code, is_primary)`.

### library_entries
| column | type | notes |
|---|---|---|
| paper_id | TEXT PK → papers | |
| added_at | INTEGER | |
| status | TEXT | `to_read` / `reading` / `read` |
| rating | INTEGER? | 1–5 |
| pdf_path | TEXT? | local file if downloaded |

### collections / collection_papers / tags / paper_tags
- `collections(id PK, name UNIQUE, created_at)`; `collection_papers(collection_id, paper_id, added_at)` PK(both).
- `tags(id PK, name UNIQUE COLLATE NOCASE)`; `paper_tags(paper_id, tag_id)` PK(both).

### notes
`notes(id PK, paper_id → papers, content TEXT /* markdown */, created_at, updated_at)`. Multiple notes per paper allowed.

### follows
| column | type | notes |
|---|---|---|
| id | INTEGER PK | |
| type | TEXT | `category` / `author` / `query` |
| value | TEXT | `cs.LG`, author name, or raw arXiv query |
| label | TEXT | display name |
| created_at | INTEGER | |
| last_synced_at | INTEGER? | **sync cursor**: next sync requests `submittedDate > last_synced_at` |
| enabled | INTEGER | bool |
| origin | TEXT | `Source.wire` of the followed feed (P-Feeds PF.2; default `'arxiv'`). Unique index is `(type, value, origin)` — the same category can be followed on multiple sources. A `category`-type follow with `value=''` is a **whole-source** follow (no category filter). |

Backup (`arxiver-backup/v2`) carries `origin` (additive-defaulted, **no schema bump** — a legacy follow with no `origin` imports as `arxiv`), so a non-arXiv follow round-trips instead of silently collapsing to arXiv.

### inbox_items
`inbox_items(paper_id PK → papers, follow_id → follows, arrived_at, state TEXT /* new / seen / saved / dismissed */, score REAL? /* similarity-to-library, Phase 3 */)`. Saving creates a `library_entries` row and marks state=saved. Dismissed rows pruned after 30 days.

### citation_edges
`citation_edges(citing_id TEXT, cited_id TEXT, source TEXT DEFAULT 's2', fetched_at, PK(citing_id, cited_id))`. Either endpoint may reference a paper not in `papers` — store **stub rows** in `papers` (title-only, `source='s2_stub'`) so FKs hold and the graph can render unfetched nodes. Recursive CTE traversal capped at depth 2 in queries.

### routine_configs / routine_dispatches
- `routine_configs(id PK, name, trigger_url, token_alias /* key into EncryptedSharedPreferences; NEVER the token */, created_at, last_used_at?)`.
- `routine_dispatches(id PK, routine_id → routine_configs, action TEXT, payload_json TEXT, status TEXT /* queued / sent / failed */, http_code INTEGER?, error TEXT?, created_at, sent_at?)`. Payloads retained for history/retry; pruned after 90 days.

### related_papers (precompute cache)
`related_papers(paper_id, neighbor_id, similarity REAL, computed_at, PK(paper_id, neighbor_id))` — top-8 per library paper, refreshed by EmbeddingWorker.

## 3. Full-text search (FTS4 + computed BM25)

> **Implementation note (revised during Phase 2):** FTS5 is not available in the platform/Robolectric SQLite builds we target, so v1 uses Room-native **FTS4 external-content tables** with BM25 computed in Kotlin from `matchinfo(…, 'pcnalx')`. Same weights and behavior as originally specced; FTS5 reconsidered in Phase 3 if we adopt a bundled SQLite for sqlite-vec.

- `papers_fts` = `@Fts4(contentEntity = PaperEntity)` over (title, abstract, authors_line) — Room maintains sync triggers automatically (the denormalized `papers.authors_line` column exists for this and for join-free list rows).
- `notes_fts` = `@Fts4(contentEntity = NoteEntity)` over (content); hits map note → paper.
- `chunk_fts` = `@Fts4(contentEntity = ChunkEmbeddingEntity)` over (chunk_text) — the keyword leg of RAG retrieval (P2.1, SPEC-SEARCH §8); the FTS rowid mirrors `chunk_embeddings.id`. Scored by BM25 (`Bm25.CHUNK_WEIGHTS`), scoped to a paper or collection.
- Ranking: Okapi BM25 from matchinfo with column weights **title 10, abstract 5, authors 3** and notes-hits weighted **2**, merged per paper (best-of). Mirrors ArxivExplorer's title-boost finding.
- Query building: user terms are quote-escaped and AND-joined; final term gets `*` prefix expansion.

## 4. Vectors (sqlite-vec)

```sql
CREATE VIRTUAL TABLE paper_embeddings USING vec0(
  paper_id TEXT PRIMARY KEY,
  embedding float[384]
);
```
- Model: bge-small-en-v1.5 (384-dim); `embedding_meta(model_name, model_rev, dim)` single-row table guards against mixing models — model change ⇒ wipe + re-embed.
- KNN: `SELECT paper_id, distance FROM paper_embeddings WHERE embedding MATCH :query AND k = 30`.
- **Fallback mode** (if extension loading blocks a phase): `paper_embeddings_blob(paper_id PK, embedding BLOB)` + chunked cosine scan in Kotlin behind the same `VectorStore` interface.

### chunk_embeddings (P2.1, RAG)
`chunk_embeddings(id INTEGER PK AUTOINCREMENT, paper_id → papers ON DELETE CASCADE, chunk_text TEXT, vector BLOB /* L2-normalized float32, same layout as paper_embeddings */, model TEXT, dim INTEGER, source_kind TEXT /* abstract | note */, ordinal INTEGER, embedded_at INTEGER, UNIQUE(paper_id, source_kind, ordinal))`. One row per text chunk for on-device RAG retrieval (SPEC-SEARCH §8). Per-row `model`/`dim` is the guard (model change ⇒ `deleteByModelMismatch` + re-index); re-indexing a paper is delete-then-insert. The synthetic `id` is the rowid that the FTS index (`chunk_fts`, §3) maps to. Cosine top-K is a chunked Kotlin scan scoped to a paper or a collection's papers.

### chat_sessions / chat_messages (P2.2, chat history)
`chat_sessions(id INTEGER PK AUTOINCREMENT, scope TEXT /* PAPER | COLLECTION */, scope_id TEXT /* paperId or collectionId */, provider_id TEXT /* ProviderId name — never a key */, created_at INTEGER, last_message_at INTEGER, pinned INTEGER NOT NULL DEFAULT 0 /* PC.4 */, title TEXT /* PC.4; nullable, null = derived label */)` indexed on `(scope, scope_id)`; `chat_messages(id INTEGER PK AUTOINCREMENT, session_id → chat_sessions ON DELETE CASCADE, role TEXT /* user | assistant */, content TEXT, status TEXT /* complete | incomplete | error */, created_at INTEGER)` indexed on `session_id`. Backs grounded Q&A (SPEC-AI-PROVIDERS chat orchestration); the KB is an existing Collection (no membership table). `status` lets a cancelled/failed streamed answer survive as a partial turn. Chat history is local conversation — **excluded from exports/backups** (red line; keys/tokens never touch it). Added by the v2→v3 migration. P2.4 adds `ChunkEmbeddingDao.collectionPapersMissingChunks` (ensure-embedded on collection chat open); the promoted chat-history list is `ChatDao.observeSessionRows` (P-Chat PC.3 — resolves the scope label + latest non-empty snippet in one JOIN, ordered `pinned DESC, last_message_at DESC`), while `observeSessions` (paper-sheet MostRecentFor resume) stays strictly `last_message_at DESC` — never pinned-first. **P-Chat PC.4 (v3→v4)** adds `pinned` + `title` and a one-shot DML repair deleting crash-artifact ghost turns (`assistant` / `incomplete` / empty `content`).

## 5. arXiv Atom → schema mapping

| Atom element | Destination |
|---|---|
| `<id>` (`http://arxiv.org/abs/2403.01234v2`) | `papers.id` = `2403.01234`, `latest_version` = 2 |
| `<title>`, `<summary>` | title, abstract (whitespace-normalized, LaTeX kept) |
| `<published>` / `<updated>` | published_at / updated_at |
| `<author><name>` (+ optional affiliation) | authors + paper_authors (affiliation dropped in v1) |
| `<arxiv:primary_category term>` | primary_category + paper_categories(is_primary=1) |
| `<category term>` | paper_categories |
| `<arxiv:comment>`, `<arxiv:journal_ref>`, `<arxiv:doi>` | comment, journal_ref, doi |
| `<link title="pdf">` | pdf_url |

Client behavior contract: ≥3s between requests (global queue), `max_results ≤ 100` per page for UI loads, exponential backoff on 5xx/429, treat empty feed with `totalResults>0` as a transient arXiv glitch (retry once).

## 6. Migrations & backup

- Room `fallbackToDestructiveMigration` is **forbidden**; every schema change ships a tested `Migration`. Schema JSONs exported to `core/database/schemas/` and committed.
- Backup/export (Phase 5): single zip = serialized JSON of all relational tables (sans `routine_configs` tokens — aliases exported, tokens never) + notes; PDFs, embeddings (`paper_embeddings`/`chunk_embeddings`), and chat history (`chat_sessions`/`chat_messages`) excluded (re-derivable / local conversation). Import = transactional upsert, then embedding backfill job.
- Single-paper citation export (P-Share PS.5): `Citation` (pure, `:core:model`) renders one [Paper] to a **BibTeX `@misc`** entry (`eprint`/`archivePrefix`/`primaryClass`, braces escaped, full author list) and a one-line **formatted reference** (`Authors (Year). Title. arXiv:id [class]. <doi-or-abs-url>`, `et al.` past three). Token-free by construction (no keys/notes/tokens touched), user-initiated → clipboard / OS share sheet (never an upload). Sibling of the bulk `LibraryExporter.toBibtex` but on the rich model. **Cited-source citations are out of scope here:** a paper's references are stored only as `s2_stub` rows (title + arXiv id; no authors/year/doi — §2 `citation_edges`), so a researcher-grade reference for them isn't derivable on-device; it stays deferred until `CitationSyncWorker` enriches author/year metadata.
- Shared PDF (P-Share PS.5): an already-downloaded paper PDF (`filesDir/pdfs/<id>v<ver>.pdf`, resolved via `PdfStorage`) is shared through the **scoped** FileProvider (`file_paths.xml` → `files-path pdfs/`) with a per-share read grant; if not on device, the action falls back to sharing the arXiv PDF **link** (no on-demand download → the arXiv rate limiter is never touched on a share).
- Whole-conversation export (P-Share PS.6): an Ask conversation exports three ways from the sheet's share menu — **share as Markdown text** (`ConversationMarkdown.conversation` → OS sheet), **export a Markdown file** (the same Markdown written to `cache/ask_exports/conversation_*.md`, shared via the PS.4 FileProvider as `text/markdown`), or **print/save-as-PDF** (`ConversationPdfPrinter`: the conversation Markdown → the chat's rich HTML (`RichHtml`) on a fixed light/print theme → the **system print pipeline** for native multi-page pagination; the KaTeX/Mermaid async-render race is handled by the same host-driven WebView readiness wait as the PS.4b PNG capture). Chat content is serialized **only** through `ConversationMarkdown` — never the importable backup schema (red line intact); user-initiated, offline (bundled assets), no upload, key untouched.
- Realized migrations: **v1→v2** (P2.1) added `chunk_embeddings` + `chunk_fts`; **v2→v3** (P2.2) added `chat_sessions` + `chat_messages`; **v3→v4** (P-Chat PC.4) added `chat_sessions.pinned` + `.title` and swept crash-artifact ghost turns (DML repair, not destructive). Each is additive, schema-JSON-committed, and validated by a `MigrationTestHelper` test under Robolectric.

## v2 — chat, knowledge base & AI keys (forward note)

The v2 AI platform adds: a **chunk-embedding** table + FTS (`chunk_embeddings` / `chunk_fts`,
RAG over abstract+notes/full text — **shipped in P2.1**, §4/§3 above; first real Room migration
v1→v2), a **chat-history** table/DAO (`chat_sessions`/`chat_messages`, scoped `PAPER` |
`COLLECTION` — **shipped in P2.2**, §4 above; migration v2→v3), and **per-provider API keys** in `EncryptedSharedPreferences` (already shipped in P1's
`AiKeyVault` — never in the DB, exports, or backups; same red line as routine tokens). The
**knowledge base = an existing Collection** (membership reuses `collection_papers`; no new
table) — a library-wide KB flag (`library_entries.in_kb`) is backlogged, not in the v2 first
cut. Each table lands with its subphase under the Room migration rule above. Plan:
`docs/P2-PLAN.md`. Architecture: `docs/SPEC-AI-PROVIDERS.md`.
