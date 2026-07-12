package dev.blokz.arxiver.core.search

/**
 * Pinned `android.os.Trace` section names shared by the trace seam (`:app` `SearchViewModel`, `:core:search`
 * `VectorIndex`) and the `TraceSectionMetric` reads in the PP.3b Macrobenchmark suites. `:core:search` is the lowest
 * module all three consumers see; because these are `const val`, `:macrobenchmark`'s reference inlines at compile
 * time — a compile-time-only coupling, no runtime dependency on `:core:search` in the benchmark process. The names
 * can't drift by construction, and `SearchTraceContractTest` pins the exact literals so a rename/typo fails CI.
 */
object SearchTrace {
    /** Async section spanning the whole suspending `runLocalSearch` — the true end-to-end D2 (includes `embedQuery`). */
    const val HYBRID_SEARCH = "hybrid_search"

    /** Sync slice around the non-suspending fusion step — NO suspension inside. */
    const val HYBRID_FUSE = "hybrid_fuse"

    /** Sync slice around the non-suspending inner scan loop, once per chunk (read with `Mode.Sum`) — NO suspension inside. */
    const val VECTOR_TOPK_SCAN = "vector_topk_scan"
}
