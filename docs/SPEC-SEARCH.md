# SPEC-SEARCH — Hybrid Search Engine

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

## 5. Semantic triage (inbox ranking)

Inbox score = max cosine similarity between the inbox paper and the user's **library centroid set**: the centroids of (up to) the 5 k-means clusters of library embeddings, recomputed weekly by `EmbeddingWorker`. Rationale (from arxiv-sanity's per-user SVM): cheap personalization from revealed preferences, no feedback UI needed in v1. Inbox sorts by score descending; score also drives a "likely relevant" section header. Cold start (library < 10 papers): inbox sorts by recency, no scores shown.

## 6. Embedding pipeline contract

- Input text: `title + "\n" + abstract`, WordPiece-tokenized, truncated to 512 tokens, mean-pooled, L2-normalized (bge-small standard usage; query prefix `"Represent this sentence for searching relevant passages: "` applied to **queries only**, per BGE convention).
- Storage: float32[384] in sqlite-vec (or BLOB fallback per SPEC-DATA §4).
- Throughput target: ≥ 5 papers/s on a 2021 mid-range device; batch job yields between batches and respects WorkManager constraints.

## 7. Testing

- Fusion math: pure-function unit tests (normalization, weighting, gate, dedupe) with synthetic legs.
- FTS: Robolectric DB tests — tokenization edge cases (hyphenated terms, unicode, LaTeX fragments in titles).
- Vector: `VectorStore` contract tests run against both sqlite-vec and the BLOB fallback implementation.
- Golden relevance set: ~20 hand-written (query → expected-paper) cases over a fixture corpus of ~100 real arXiv abstracts; hybrid must hit expected paper in top 5 for ≥ 80% of cases. Guards tuning regressions.
