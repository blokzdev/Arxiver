package dev.blokz.arxiver.core.model

/**
 * Source-polymorphic identity of a [Paper] (SPEC-P-SOURCES §2) — the single seam between the opaque
 * `papers.id` primary key ([storageId]) and source-specific behaviour (arXiv URL synthesis lives ONLY
 * on [ArxivRef]).
 *
 * ## `storageId` is a DB primary key, NOT a URL
 * arXiv rows are **bare** (`2403.09999`) — byte-identical to pre-P-Sources ids, so nothing is re-keyed.
 * Non-arXiv rows are **namespaced** with a reserved prefix (`chemrxiv:10.26434/…`). No percent/URL
 * encoding is applied here — the id is a PK; URL-safety is applied downstream only where the id enters a
 * URL/route (`Routes.paperDetail` `Uri.encode`; `HtmlStorage` `/`→`_`). Dispatch splits on the FIRST
 * `:` only, so a native id with further `:`/`/` round-trips losslessly. (SPEC-P-SOURCES originally said
 * "namespaced composite through `Uri.encode`" — un-implementable in this pure-JVM `:core:model` module;
 * the opaque-PK first-colon scheme is the ratified deviation, recorded in SPEC §2/§8 + the ROADMAP
 * Decision log in the landing commit.)
 */
sealed interface PaperRef {
    /** The exact `papers.id` PK. A bare arXiv id, or `"<origin.wire>:<nativeId>"`. */
    val storageId: String

    /** Identity origin — the `papers.origin` discriminator column. */
    val origin: Source

    /** Source native id (DOI, etc.); `null` for arXiv (its bare id IS the native id). */
    val nativeId: String?

    /** The wrapped [ArxivId] iff this is an [ArxivRef], else null — the PS.0 `Paper.id` shim escape hatch. */
    val arxivIdOrNull: ArxivId?

    companion object {
        /**
         * Reconstruct a [PaperRef] from a stored `papers.id`. The PK is the single source of truth: an
         * un-prefixed id (no reserved prefix before the first `:`) is arXiv; a reserved prefix yields an
         * [ExternalRef]. Splits on the first `:` only, so a native id may itself contain `:`/`/`.
         */
        fun fromStorageId(storageId: String): PaperRef {
            val prefix = storageId.substringBefore(':', missingDelimiterValue = "")
            val source = Source.BY_PREFIX[prefix]
            return if (source == null) {
                ArxivRef(ArxivId(storageId))
            } else {
                ExternalRef(source, storageId.substringAfter(':'), storedPdfUrl = null)
            }
        }
    }
}

/**
 * The ONLY ref that synthesizes arXiv URLs. Wraps the API-stable [ArxivId] and delegates all URL building
 * to it, so arXiv behaviour is byte-identical to pre-P-Sources code. (A plain data class, not a value
 * class: `PaperRef`-typed usage always boxes, so a value class buys no allocation win — only a
 * sharper-edged type. Structural equality via the wrapped [ArxivId] is preserved either way.)
 */
data class ArxivRef(val id: ArxivId) : PaperRef {
    override val storageId: String get() = id.value
    override val origin: Source get() = Source.ARXIV
    override val nativeId: String? get() = null
    override val arxivIdOrNull: ArxivId get() = id

    fun absUrl(version: Int? = null): String = id.absUrl(version)

    fun pdfUrl(version: Int? = null): String = id.pdfUrl(version)

    fun htmlUrl(version: Int? = null): String = id.htmlUrl(version)
}

/**
 * A non-arXiv paper identity. Synthesizes NOTHING — a source's PDF/abs URLs are stored explicitly
 * ([storedPdfUrl]), never computed (there is no universal cross-source URL scheme). PS.0 constructs these
 * only in tests; no runtime path produces one until PS.1.
 *
 * [nativeId] is a NON-NULL ctor param (narrowing the nullable [PaperRef.nativeId]) so an [ExternalRef] can
 * never carry a null native id — that would poison the PK as `"chemrxiv:null"`.
 */
data class ExternalRef(
    override val origin: Source,
    override val nativeId: String,
    val storedPdfUrl: String? = null,
) : PaperRef {
    init {
        require(origin != Source.ARXIV) { "ExternalRef must not carry Source.ARXIV — use ArxivRef" }
    }

    override val storageId: String get() = "${origin.wire}:$nativeId"

    override val arxivIdOrNull: ArxivId? get() = null
}
