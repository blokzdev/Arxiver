# SPEC-SEARCH — Hybrid Search Engine



> **P5.1 — the ranker is now measurable.** An offline, on-device eval (`:core:search/eval`) scores the live
> Rocchio construction over the user's own labels (saves ∪ feedback; PU-weighted; Kish-ESS floors; segmented on
> `abstract = ''` — the P-Explorer mixed-quality axis) with a seeded bootstrap CI, a fold contract that excludes
> held-out ids from positives/negatives/cold-start seeds alike, and regime honesty (a fold that ran a different
> model regime than production flags the report). Runs once per EmbeddingWorker pass; zero egress (structurally
> tested); results surface only in a debug Settings card. Every later P5 promotion (shrinkage λ, calibration,
> the gated head's β) must clear this harness per-segment.

> **P5.2 shrinkage + P5.3 calibrated threshold.** The positive centroids are nearest-shrunken toward their mean by
> a per-user λ the harness selects on a pre-registered grid (k-fold select, time-split confirm; 0 below the floor —
> bit-identical to the unshrunk scorer). `inbox_items.score` stays the RAW Rocchio value; a per-user **Platt map**
> `p = σ(a·s + b)` fitted on the harness's held-out outputs (≥50 labels + ≥10 effective negatives, else null;
> `a > 0` enforced — monotone, so ordering NEVER reshuffles) is persisted in the single-row `relevance_model` table,
> and Today translates the p = 0.5 point ONCE into a raw-score cut. Uncalibrated profiles keep EXACTLY the legacy
> 0.55. **"Likely relevant" is top-k (10)** above that cut, not everything above it. `paper_feedback.score_at_label`
> records the score the user saw at label time (exposure context for future eval; NULL pre-v14).

> **Multi-source online search (P-Explorer PE.3):** the Explore Online scope searches ONE source per submit —
> arXiv natively (this spec's §2 pipeline, unchanged), any other source via a single OpenAlex `search()` call
> (host `api.openalex.org`, ~1.2s self-spacing, metered → explicit-submit-only + un-paginated v1). The local
> hybrid pipeline below is untouched by source choice. bio/medRxiv keyword search rides OpenAlex (their native
> API has none) and may lag their native follow feed.

**Status:** Approved · Implemented in `:core:search`

## 1. Search modes

| Mode | Corpus | Engine | Availability |
|---|---|---|---|
| **Local** | library + inbox + cached papers | FTS5 + vector fusion (below) | always, offline |
| **arXiv (online)** | all of arXiv | arXiv API query | network required |

The search screen exposes both as tabs; queries are shared between them. Local is default.

## 2. Query handling

- Raw input is used both as FTS string and embedding input.
- Field prefixes `ti:` `au:` `abs:` `cat:` and quoted phrases are parsed into a structured `Query`:
  - **Local:** mapped to FTS5 column filters (`title:`, `authors:`…) and category WHERE clauses; field-scoped queries skip the semantic leg unless free text remains (embedding a bare `cat:cs.LG` is meaningless).
  - **Online:** mapped to arXiv API syntax verbatim.
- Filters (UI chips): category, date range, library-only, tag, reading status — applied as SQL predicates on both legs.

## 3. Hybrid fusion (local mode)

Recipe adapted from ArxivExplorer's production-validated parameters:

```
parallel:
  keyword leg:  FTS5 MATCH, bm25 weights (title 10, abstract 5, authors 3, notes 2) → top 30
  semantic leg: embed(query) → vec KNN cosine → top 30        [skipped if no embeddings yet]

normalize:    each leg's scores min-max → [0,1]   (bm25 negated first: lower = better in SQLite)
fuse:         score = 0.25 * kw + 0.75 * sem      (paper in one leg only: its weighted score stands)
dedupe:       by paper_id, keep fused score
quality gate: drop results with score < 0.70 * best_score
emit:         top 20, with per-leg provenance (badges: "keyword" / "semantic" / "both")
```

- Weights and gate are constants in one config object (`SearchTuning`) — tunable without surgery, surfaced in debug builds only.
- If the embedding model isn't downloaded yet, local search degrades to pure FTS5 silently (plus a one-time hint to enable semantic search).
- Latency budget: < 300ms end-to-end on a 5K-paper corpus (FTS ~10ms; vec KNN ~30ms; query embedding ~100–200ms dominates — embed on `Dispatchers.Default`, cache last 32 query embeddings in an LRU).

## 4. Related papers

For every **library** paper: top-8 nearest neighbors (cosine) over the whole local corpus, excluding self; stored in `related_papers`; refreshed by `EmbeddingWorker` when new papers are embedded (recompute only for papers whose neighborhood could change — i.e. full refresh per batch is acceptable at our scale, optimize later if needed). Detail screen shows them with similarity bars; tapping a non-library neighbor offers save.

**Relation graphs (P-Atlas PA.1).** `RelationGraphBuilder` (pure, here in `:core:search`) turns a `RelationGraph` (nodes + similarity/citation edges) into a valid-by-construction Mermaid flowchart for the chat surface — the app draws the structure, the AI narrates. Node budget ~12 (phone legibility), edges deduped, labels escaped for Mermaid `securityLevel:'strict'` (see SPEC-RICH-OUTPUT §10). The feeder (`RelationGraphRepository`, `:app`) prefers the precomputed `related_papers` neighbors and falls back to a live `VectorIndex.topK` scan for a freshly-embedded paper, plus `citationDao` edges. The cosine/edge math is the same `dotSimilarity` used by `relations` (DispatchRepository); that builder should eventually feed `RelationGraphBuilder` rather than re-deriving its own copy.

## 5. Semantic triage (inbox ranking)

Inbox score = a two-sided **Rocchio relevance** in `[0,1]`: `clamp01(α·maxCosine(v, positiveCentroids) − γ·cosine(v, negativeCentroid))`, with `α = 1.0`, `γ = 0.5` (tunable constants). **Positive centroids** are the (up to 5) k-means clusters of the user's library saves **plus explicit thumbs-up** papers; the **negative centroid** is the pooled, re-normalized mean of **dismissed and thumbs-down** papers (≥ 3 required), read from the durable `paper_feedback` table so a dismiss survives inbox pruning. Recomputed by `InboxScorer` (driven by `EmbeddingWorker`) on every periodic run (~6 h) and on every manual sync. **The scale is preserved**: with no negatives the score is bit-identical to the prior positive-only `maxCosine` — a purely additive deepen — so the `[0,1]` range, the `score DESC` ordering, and the ≥ 0.55 "Likely relevant" section header are unchanged. **Cold start** (library < 10 papers): seed the positive set from papers the user's *enabled follows* have already surfaced into the inbox (minus any disliked ones), so a follows-only user gets ranking instead of pure recency; only a truly empty profile (no library, no follows) falls back to recency with no scores. Rationale (from arxiv-sanity): cheap personalization from revealed preferences (saves **and** dismisses) plus an optional explicit thumbs signal. *(The literal arxiv-sanity logistic/linear-SVM classifier is a tracked follow-on — Rocchio is the deterministic, scale-preserving first step; see ROADMAP Phase P4.)*

## 6. Embedding pipeline contract

- Input text: `title + "\n" + abstract`, WordPiece-tokenized, truncated to 512 tokens, mean-pooled, L2-normalized (bge-small standard usage; query prefix `"Represent this sentence for searching relevant passages: "` applied to **queries only**, per BGE convention).
- Storage: float32[384] in sqlite-vec (or BLOB fallback per SPEC-DATA §4).
- Throughput target: ≥ 5 papers/s on a 2021 mid-range device; batch job yields between batches and respects WorkManager constraints.

## 7. Testing

- Fusion math: pure-function unit tests (normalization, weighting, gate, dedupe) with synthetic legs.
- FTS: Robolectric DB tests — tokenization edge cases (hyphenated terms, unicode, LaTeX fragments in titles).
- Vector: `VectorStore` contract tests run against both sqlite-vec and the BLOB fallback implementation.
- Two-sided ranker (P4): pure-function tests over synthetic clusters — a dismiss-cluster paper ranks below a save-cluster paper; **empty negatives ≡ positive-only similarity**; deterministic; output ∈ [0,1] (`RocchioRankerTest`). Scorer wiring against real DAOs — dismiss demotes, follows cold-start yields scores (not recency), an empty profile stays null, stale-model vectors are skipped (`InboxScorerTest`).
- Golden relevance set: ~20 hand-written (query → expected-paper) cases over a fixture corpus of ~100 real arXiv abstracts; hybrid must hit expected paper in top 5 for ≥ 80% of cases. Guards tuning regressions.

## 8. RAG retrieval (P2)

Chat-with-paper / KB chat (P2) grounds generation in the library via **on-device** retrieval.
Implemented in P2.1 (`:core:search` + `:core:database`); reuses the embedding model and
`HybridFusion`. Only the retrieved chunks + the question ever leave the device (and only for a
cloud provider, behind a "what leaves the device" confirm — SPEC-AI-PROVIDERS §5). Plan:
`docs/P2-PLAN.md`.

- **Chunking** (`TextChunker`, pure): `title + "\n" + abstract` and each note body are split into
  sentence-aware chunks under a character budget (a tokenizer-free proxy for bge's 512-token
  window) with small overlap, so passages past the window stay retrievable. Abstract chunks carry
  `source_kind = abstract`; note chunks `source_kind = note`, numbered continuously per paper.
  (Full PDF text becomes a third source once P3 lands.)
- **Chunk index** (`chunk_embeddings`, SPEC-DATA §4): one row per chunk — text + L2-normalized
  float32 embedding (`EmbeddingService.embedPassages`, no query prefix) + per-row `model`/`dim`
  guard. An external-content FTS4 `chunk_fts` mirrors `chunk_text` for the keyword leg.
- **Indexing** (`RagIndexer`): re-chunk + re-embed a paper, delete-then-insert (idempotent;
  model-guarded). **Eager backfill**: `EmbeddingWorker` chunk-indexes library papers lacking
  current-model chunks on the unmetered job, bounded per run; ad-hoc (non-library) papers index
  on demand.
- **Retrieval** (`RagRetriever`, scoped to a `Paper` or a `Collection`): semantic leg = cosine
  top-K over the scope's chunk vectors (chunked scan, like `VectorIndex`); keyword leg = `chunk_fts`
  BM25; fused by the shared `HybridFusion` (25/75, 0.70 gate) keyed on chunk id, returning
  `RetrievedChunk(text, paperId, score, provenance)`. The caller embeds the query
  (`EmbeddingService.embedQuery`); a null query vector degrades to keyword-only.

Architecture: `docs/SPEC-AI-PROVIDERS.md`.
