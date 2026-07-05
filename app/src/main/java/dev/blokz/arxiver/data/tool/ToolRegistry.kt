package dev.blokz.arxiver.data.tool

import dev.blokz.arxiver.core.ai.ToolCall
import dev.blokz.arxiver.core.ai.ToolDef
import dev.blokz.arxiver.core.ai.ToolResult
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.database.fts.KeywordHit
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ExternalPaperDraft
import dev.blokz.arxiver.core.model.ExternalRef
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.network.arxiv.SearchFilter
import dev.blokz.arxiver.core.network.chemrxiv.ChemRxivItemHit
import dev.blokz.arxiver.core.network.chemrxiv.ChemRxivSearchResponse
import dev.blokz.arxiver.core.network.s2.S2SearchPaper
import dev.blokz.arxiver.core.network.s2.S2SearchResponse
import dev.blokz.arxiver.core.search.HybridFusion
import dev.blokz.arxiver.data.PaperPage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Serializable
private data class LibraryHitDto(
    val paperId: String,
    val title: String,
    val abstractSnippet: String,
    val score: Double,
    val provenance: String,
    val inLibrary: Boolean,
)

@Serializable
private data class LibrarySearchResult(
    val degraded: Boolean,
    val hits: List<LibraryHitDto>,
)

@Serializable
private data class ArxivHitDto(
    val arxivId: String,
    val title: String,
    val authors: List<String>,
    val abstractSnippet: String,
    val primaryCategory: String,
    val published: String,
)

@Serializable
private data class ArxivSearchResult(val hits: List<ArxivHitDto>)

@Serializable
private data class PaperDetailDto(
    val arxivId: String,
    val title: String,
    val authors: List<String>,
    val abstract: String,
    val primaryCategory: String,
    val latestVersion: Int,
    val published: String,
)

@Serializable
private data class ImportResult(
    val imported: Boolean,
    val alreadyInLibrary: Boolean,
    val title: String? = null,
)

@Serializable
private data class S2ExternalIdsDto(
    val arxiv: String? = null,
    val doi: String? = null,
    val pubmed: String? = null,
)

@Serializable
private data class S2HitDto(
    val paperId: String?,
    val title: String,
    val abstractSnippet: String?,
    val tldr: String?,
    val year: Int?,
    val venue: String?,
    val authors: List<String>,
    val citationCount: Int?,
    val externalIds: S2ExternalIdsDto,
    val openAccessPdf: String?,
    // True iff the hit carries a parseable arXiv id — tells the model up front which hits
    // `import_to_library` accepts (S2's non-arXiv results are read-only; PT.3 §6).
    val importable: Boolean,
)

@Serializable
private data class S2SearchResult(val hits: List<S2HitDto>)

@Serializable
private data class ChemHitDto(
    val title: String,
    val abstractSnippet: String?,
    val doi: String?,
    val authors: List<String>,
    val publishedDate: String?,
    val pdfUrl: String? = null,
    // True iff the hit has BOTH a DOI (its identity key) and a resolvable PDF — tells the model which hits
    // `import_to_library` accepts via `source:chemrxiv` + the hit's `doi` (PS.1; the draft is cached for it).
    val importable: Boolean,
)

@Serializable
private data class ChemSearchResult(val hits: List<ChemHitDto>)

/** LOCAL tools run purely on-device (zero egress); EXTERNAL tools leave the device (arXiv, PT.2+). */
private enum class ToolClass { LOCAL, EXTERNAL }

/** A tool = its wire definition + its egress class + its handler. Consent gates by class, not by name. */
private class ToolSpec(
    val def: ToolDef,
    val toolClass: ToolClass,
    val handler: suspend (ToolCall, ToolContext) -> ToolExecution,
)

/**
 * The P-Tools tool catalog + executor. LOCAL: `search_my_library` runs the on-device hybrid search
 * over the user's saved library (PT.1). EXTERNAL (PT.2): `search_arxiv`, `get_paper`, `import_to_library`
 * reach the live arXiv API — **the first tools that leave the device**.
 *
 * **Consent is two-gated by tool CLASS** ([ToolConsent.library] / [ToolConsent.external]): [toolDefs]
 * offers exactly the enabled subset, and [execute] additionally refuses an EXTERNAL call in a web-off
 * turn *before* the seam runs (defense-in-depth for the first egress). [ToolExecution.egress] is `false`
 * for LOCAL and `true` on EVERY external path (incl. errors) so the activity log never under-discloses.
 *
 * **No-bypass by construction:** the collaborators are injected as pure functional seams. LOCAL seams
 * import NO `:core:network`; EXTERNAL seams route through `PaperRepository` → `ArxivApiClient`, which
 * `acquire()`s the shared arXiv limiter before the socket on the `@ArxivClient` (AllowedHosts-gated)
 * client. The registry receives repository lambdas, never an HTTP client (a structural test forbids a
 * direct HTTP import in this package). `execute` never throws — a failure becomes a recoverable result.
 */
class ToolRegistry(
    /** Keyword (BM25) leg over a wide candidate pool, notes-gated by the 2nd arg — private notes never
     *  influence a cloud turn. The caller restricts the pool to the library BEFORE fusion. */
    private val keywordSearch: suspend (query: String, includeNotes: Boolean, limit: Int) -> List<KeywordHit>,
    /** Semantic (embedding) leg → (paperId, similarity); **null when the model isn't ready / embed failed**. */
    private val semanticSearch: suspend (query: String, k: Int) -> List<Pair<String, Double>>?,
    /** The ids of papers in the user's saved library — the `inLibrary` filter. */
    private val libraryPaperIds: suspend () -> Set<String>,
    /** Hydrate papers for semantic-only hits the keyword leg didn't already return. */
    private val paperById: suspend (ids: List<String>) -> List<PaperEntity>,
    /** EXTERNAL: live arXiv search through the rate-limited fetch client (`search_arxiv`). */
    private val searchArxiv: suspend (filter: SearchFilter, maxResults: Int) -> AppResult<PaperPage>,
    /** EXTERNAL: fetch one paper (cache-first) — persists the `papers` row (`get_paper`, `import_to_library`). */
    private val getPaper: suspend (id: ArxivId) -> Paper?,
    /** Persist a `library_entries` row for an already-cached `papers` id (`import_to_library`). */
    private val savePaper: suspend (paperId: String) -> Unit,
    /** Library membership check — the idempotency short-circuit for `import_to_library`. */
    private val isInLibrary: suspend (paperId: String) -> Boolean,
    /** EXTERNAL: Semantic Scholar search through the host-gated, 1.2s-spaced S2 client (`search_semantic_scholar`). */
    private val searchSemanticScholar:
        suspend (
            query: String,
            limit: Int,
            venue: String?,
            yearFrom: Int?,
            yearTo: Int?,
        ) -> AppResult<S2SearchResponse>,
    /** EXTERNAL: chemRxiv (Open Engage) search through the host-gated, 1.2s-spaced client (`search_chemrxiv`). */
    private val searchChemRxiv: suspend (term: String, limit: Int, skip: Int) -> AppResult<ChemRxivSearchResponse>,
    /** Persist a non-arXiv search hit ([ExternalPaperDraft]) as a `papers` row — NO network (chemRxiv import). */
    private val importExternal: suspend (draft: ExternalPaperDraft) -> Paper,
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Bounded LRU of importable chemRxiv drafts, keyed by DOI: populated at SEARCH time (with the full,
     * untruncated abstract from the raw item), read at IMPORT time so import never re-fetches (chemRxiv has
     * no get-by-DOI endpoint; a re-search would cost a second egress + politeness slot). Public metadata
     * only — no tokens, no bytes (the red lines hold for an in-memory cache). A stale/evicted entry yields a
     * clean "search first" error, never a fetch and never a fork. Synchronized: the tool loop is sequential,
     * but this guards defensively against interleaved turns.
     */
    private val chemDrafts: MutableMap<String, ExternalPaperDraft> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, ExternalPaperDraft>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, ExternalPaperDraft>): Boolean =
                    size > CHEM_DRAFT_CACHE_SIZE
            },
        )

    private val specs: List<ToolSpec> =
        listOf(
            ToolSpec(SEARCH_MY_LIBRARY, ToolClass.LOCAL, ::handleSearchLibrary),
            ToolSpec(SEARCH_ARXIV, ToolClass.EXTERNAL, ::handleSearchArxiv),
            ToolSpec(GET_PAPER, ToolClass.EXTERNAL, ::handleGetPaper),
            ToolSpec(IMPORT_TO_LIBRARY, ToolClass.EXTERNAL, ::handleImport),
            ToolSpec(SEARCH_SEMANTIC_SCHOLAR, ToolClass.EXTERNAL, ::handleSearchS2),
            ToolSpec(SEARCH_CHEMRXIV, ToolClass.EXTERNAL, ::handleSearchChem),
        )
    private val byName: Map<String, ToolSpec> = specs.associateBy { it.def.name }

    /** Offer exactly the subset the user consented to, gated by each tool's egress class. */
    override fun toolDefs(consent: ToolConsent): List<ToolDef> =
        specs.filter {
            when (it.toolClass) {
                ToolClass.LOCAL -> consent.library
                ToolClass.EXTERNAL -> consent.external
            }
        }.map { it.def }

    override suspend fun execute(
        call: ToolCall,
        context: ToolContext,
    ): ToolExecution {
        val spec = byName[call.name] ?: return errorResult(call, "", "unknown tool", egress = false)
        val external = spec.toolClass == ToolClass.EXTERNAL
        // Execute-time consent guard (red-line for the first egress): a hallucinated external call in a
        // web-off turn is refused BEFORE the seam runs — nothing egresses without the user's web consent.
        if (external && !context.externalEnabled) {
            return errorResult(call, "", "web search not enabled", egress = true)
        }
        // execute MUST NOT throw — a seam failure becomes an error result the model can recover from.
        return runCatching { spec.handler(call, context) }
            .getOrElse { errorResult(call, "", "tool failed", egress = external) }
    }

    // --- LOCAL: search_my_library (zero egress) ---

    private suspend fun handleSearchLibrary(
        call: ToolCall,
        context: ToolContext,
    ): ToolExecution {
        val args = parseArgs(call)
        val query = args?.stringAt("query").orEmpty().trim()
        if (query.isBlank()) return errorResult(call, query, "empty query", egress = false)
        val k = (args?.intAt("k") ?: DEFAULT_K).coerceIn(1, MAX_K)
        return searchLibrary(context, call, query, k)
    }

    private suspend fun searchLibrary(
        context: ToolContext,
        call: ToolCall,
        query: String,
        k: Int,
    ): ToolExecution {
        val librarySet = libraryPaperIds()
        if (librarySet.isEmpty()) return okLibrary(call, query, degraded = false, hits = emptyList())

        // Restrict BOTH legs to the user's LIBRARY *before* fusion. The `papers` corpus is a superset
        // of the library (browse/search results are cached into `papers` too), so fusing over the whole
        // corpus would let HybridFusion's quality gate + resultLimit drop/crowd-out library papers
        // before any library filter ran — the tool could return ZERO library hits for a real match.
        // A wide candidate pool per leg keeps a library paper from being truncated by each leg's top-N.
        val keywordHits =
            keywordSearch(query, context.includeNotes, CANDIDATE_LIMIT).filter { it.paper.id in librarySet }
        val papersById = keywordHits.associateTo(mutableMapOf()) { it.paper.id to it.paper }
        val keywordLeg = keywordHits.map { it.paper.id to it.score }

        // null ⇒ the semantic path couldn't run (model not ready / embed failed) ⇒ keyword-only degrade.
        val semantic = semanticSearch(query, CANDIDATE_LIMIT)
        val degraded = semantic == null
        val semanticLeg = semantic?.filter { it.first in librarySet }.orEmpty()

        val fused = HybridFusion.fuse(keyword = keywordLeg, semantic = semanticLeg).take(k)

        val missing = fused.map { it.paperId }.filter { it !in papersById }
        if (missing.isNotEmpty()) paperById(missing).forEach { papersById[it.id] = it }

        val hits =
            fused.mapNotNull { hit ->
                papersById[hit.paperId]?.let { p ->
                    LibraryHitDto(
                        paperId = p.id,
                        title = p.title,
                        abstractSnippet = p.abstract.take(ABSTRACT_SNIPPET_CHARS),
                        score = hit.score,
                        provenance = hit.provenance.name,
                        inLibrary = true,
                    )
                }
            }
        return okLibrary(call, query, degraded, hits)
    }

    // --- EXTERNAL: search_arxiv / get_paper / import_to_library (egress = true on every path) ---

    private suspend fun handleSearchArxiv(
        call: ToolCall,
        context: ToolContext,
    ): ToolExecution {
        val args = parseArgs(call)
        val query = args?.stringAt("query").orEmpty().trim()
        if (query.isBlank()) return errorResult(call, "", "empty query", egress = true)
        val filter =
            SearchFilter(
                term = query,
                categories = args?.stringAt("category")?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty(),
                from = args?.stringAt("date_range.from")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                to = args?.stringAt("date_range.to")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            )
        val max = (args?.intAt("max_results") ?: DEFAULT_ARXIV_RESULTS).coerceIn(1, MAX_ARXIV_RESULTS)
        return when (val r = searchArxiv(filter, max)) {
            is AppResult.Success -> okArxivSearch(call, query, r.value.papers)
            is AppResult.Failure -> errorResult(call, query, "search failed", egress = true)
        }
    }

    private suspend fun handleGetPaper(
        call: ToolCall,
        context: ToolContext,
    ): ToolExecution {
        val raw = parseArgs(call)?.stringAt("arxiv_id").orEmpty()
        // Parse to the un-versioned id (strips vN/URLs) so the paper caches under the same key the
        // reader/PDF routes navigate by — a bare ArxivId(raw) would cache-miss on a `2403.01234v2` input.
        val id = ArxivId.parse(raw)?.first ?: return errorResult(call, raw, "bad id", egress = true)
        val paper = getPaper(id) ?: return errorResult(call, id.value, "not found", egress = true)
        return okPaperDetail(call, id.value, paper)
    }

    private suspend fun handleImport(
        call: ToolCall,
        context: ToolContext,
    ): ToolExecution {
        val args = parseArgs(call)
        // Source-aware: `source:chemrxiv` saves a cached chemRxiv draft (no network); the default (and any
        // arXiv id) takes the existing arXiv Atom path verbatim.
        return if (args?.stringAt("source")?.lowercase() == "chemrxiv") {
            handleImportChemrxiv(call, args)
        } else {
            handleImportArxiv(call, args)
        }
    }

    private suspend fun handleImportArxiv(
        call: ToolCall,
        args: JsonObject?,
    ): ToolExecution {
        val raw = args?.stringAt("arxiv_id").orEmpty()
        val id = ArxivId.parse(raw)?.first ?: return errorResult(call, raw, "bad id", egress = true)
        // Idempotent: a library short-circuit avoids any fetch; @Upsert makes a re-save a no-op regardless.
        if (isInLibrary(id.value)) return okImport(call, id.value, imported = false, already = true, title = null)
        // FK-mandated ordering: persist the `papers` row FIRST (getPaper), THEN the `library_entries` row
        // (savePaper) — LibraryEntryEntity FKs PaperEntity, and observeLibrary() is an INNER JOIN.
        val paper = getPaper(id) ?: return errorResult(call, id.value, "not found", egress = true)
        savePaper(id.value)
        return okImport(call, id.value, imported = true, already = false, title = paper.title)
    }

    private suspend fun handleImportChemrxiv(
        call: ToolCall,
        args: JsonObject?,
    ): ToolExecution {
        val doi = args?.stringAt("doi").orEmpty()
        val draft =
            chemDrafts[doi]
                ?: return errorResult(call, doi, "unknown chemRxiv paper — search chemRxiv first", egress = true)
        // Deterministic storageId ("chemrxiv:<doi>") short-circuits BEFORE any write, mirroring the arXiv
        // path's isInLibrary check — there is no network cost on either branch.
        val storageId = ExternalRef(Source.CHEMRXIV, doi).storageId
        if (isInLibrary(storageId)) {
            return okImport(call, storageId, imported = false, already = true, title = draft.title)
        }
        val paper = importExternal(draft) // persists the `papers` row from the draft — no network
        savePaper(paper.ref.storageId) // library entry — the origin-blind seam, unchanged
        return okImport(call, paper.ref.storageId, imported = true, already = false, title = paper.title)
    }

    private suspend fun handleSearchS2(
        call: ToolCall,
        context: ToolContext,
    ): ToolExecution {
        val args = parseArgs(call)
        val query = args?.stringAt("query").orEmpty().trim()
        if (query.isBlank()) return errorResult(call, "", "empty query", egress = true)
        val limit = (args?.intAt("limit") ?: DEFAULT_S2_RESULTS).coerceIn(1, MAX_S2_RESULTS)
        val venue = args?.stringAt("venue")?.takeIf { it.isNotBlank() }
        val from = args?.intAt("year.from")
        val to = args?.intAt("year.to")
        return when (val r = searchSemanticScholar(query, limit, venue, from, to)) {
            is AppResult.Success -> okS2Search(call, query, r.value.data)
            is AppResult.Failure -> errorResult(call, query, "search failed", egress = true)
        }
    }

    private suspend fun handleSearchChem(
        call: ToolCall,
        context: ToolContext,
    ): ToolExecution {
        val args = parseArgs(call)
        val term = args?.stringAt("term").orEmpty().trim()
        if (term.isBlank()) return errorResult(call, "", "empty query", egress = true)
        val limit = (args?.intAt("limit") ?: DEFAULT_CHEM_RESULTS).coerceIn(1, MAX_CHEM_RESULTS)
        val skip = (args?.intAt("skip") ?: 0).coerceAtLeast(0)
        return when (val r = searchChemRxiv(term, limit, skip)) {
            is AppResult.Success -> okChemSearch(call, term, r.value.itemHits)
            is AppResult.Failure -> errorResult(call, term, "search failed", egress = true)
        }
    }

    // --- result builders ---

    private fun okLibrary(
        call: ToolCall,
        query: String,
        degraded: Boolean,
        hits: List<LibraryHitDto>,
    ): ToolExecution {
        val body = json.encodeToString(LibrarySearchResult.serializer(), LibrarySearchResult(degraded, hits))
        val leg = if (degraded) "keyword-only" else "hybrid"
        return ToolExecution(
            result = ToolResult(call.id, call.name, body, isError = false),
            query = query,
            resultSummary = "${hits.size} results ($leg)",
            egress = false,
        )
    }

    private fun okArxivSearch(
        call: ToolCall,
        query: String,
        papers: List<Paper>,
    ): ToolExecution {
        val hits =
            papers.map { p ->
                ArxivHitDto(
                    arxivId = p.id.value,
                    title = p.title,
                    authors = p.authors,
                    abstractSnippet = p.abstract.take(ABSTRACT_SNIPPET_CHARS),
                    primaryCategory = p.primaryCategory,
                    published = p.publishedAt.toString(),
                )
            }
        val body = json.encodeToString(ArxivSearchResult.serializer(), ArxivSearchResult(hits))
        return ToolExecution(
            result = ToolResult(call.id, call.name, body, isError = false),
            query = query,
            resultSummary = "${hits.size} results from arXiv",
            egress = true,
        )
    }

    private fun okS2Search(
        call: ToolCall,
        query: String,
        papers: List<S2SearchPaper>,
    ): ToolExecution {
        val hits =
            papers.map { p ->
                val arxiv = p.externalIds?.ArXiv
                S2HitDto(
                    paperId = p.paperId,
                    title = p.title.orEmpty(),
                    abstractSnippet = p.abstract?.take(ABSTRACT_SNIPPET_CHARS),
                    tldr = p.tldr?.text?.take(ABSTRACT_SNIPPET_CHARS),
                    year = p.year,
                    venue = p.venue?.takeIf { it.isNotBlank() },
                    authors = p.authors.mapNotNull { it.name },
                    citationCount = p.citationCount,
                    externalIds =
                        S2ExternalIdsDto(
                            arxiv = arxiv,
                            doi = p.externalIds?.DOI,
                            pubmed = p.externalIds?.PubMed,
                        ),
                    openAccessPdf = p.openAccessPdf?.url,
                    // The exact gate handleImport uses — a hit is importable iff its arXiv id parses.
                    importable = ArxivId.parse(arxiv.orEmpty())?.first != null,
                )
            }
        val body = json.encodeToString(S2SearchResult.serializer(), S2SearchResult(hits))
        return ToolExecution(
            result = ToolResult(call.id, call.name, body, isError = false),
            query = query,
            resultSummary = "${hits.size} results from Semantic Scholar",
            egress = true,
        )
    }

    private fun okChemSearch(
        call: ToolCall,
        query: String,
        itemHits: List<ChemRxivItemHit>,
    ): ToolExecution {
        val hits =
            itemHits.mapNotNull { it.item }.map { item ->
                val authorNames =
                    item.authors
                        .map { a ->
                            // An author carries a combined `name` OR separate first/last (mirrors the API).
                            a.name?.takeIf { it.isNotBlank() }
                                ?: listOfNotNull(a.firstName, a.lastName).joinToString(" ").trim()
                        }
                        .filter { it.isNotBlank() }
                // Top-level pdfUrl when present, else the nested asset original (mirrors the API).
                val pdf = item.pdfUrl ?: item.asset?.original?.url
                val doi = item.doi
                // Importable iff it has BOTH a DOI (identity) and a PDF (readable). Cache the draft NOW, from
                // the RAW item — before the abstract is truncated to a snippet — so the stored paper keeps the
                // whole abstract as its offline read surface.
                if (doi != null && pdf != null) {
                    chemDrafts[doi] =
                        ExternalPaperDraft(
                            origin = Source.CHEMRXIV,
                            nativeId = doi,
                            title = item.title.orEmpty(),
                            abstract = item.abstract.orEmpty(),
                            authors = authorNames,
                            publishedAt = item.publishedDate.toInstantOrEpoch(),
                            pdfUrl = pdf,
                        )
                }
                ChemHitDto(
                    title = item.title.orEmpty(),
                    abstractSnippet = item.abstract?.take(ABSTRACT_SNIPPET_CHARS),
                    doi = doi,
                    authors = authorNames,
                    publishedDate = item.publishedDate,
                    pdfUrl = pdf,
                    importable = doi != null && pdf != null,
                )
            }
        val body = json.encodeToString(ChemSearchResult.serializer(), ChemSearchResult(hits))
        return ToolExecution(
            result = ToolResult(call.id, call.name, body, isError = false),
            query = query,
            resultSummary = "${hits.size} results from chemRxiv",
            egress = true,
        )
    }

    private fun okPaperDetail(
        call: ToolCall,
        query: String,
        paper: Paper,
    ): ToolExecution {
        val dto =
            PaperDetailDto(
                arxivId = paper.id.value,
                title = paper.title,
                authors = paper.authors,
                abstract = paper.abstract,
                primaryCategory = paper.primaryCategory,
                latestVersion = paper.latestVersion,
                published = paper.publishedAt.toString(),
            )
        val body = json.encodeToString(PaperDetailDto.serializer(), dto)
        return ToolExecution(
            result = ToolResult(call.id, call.name, body, isError = false),
            query = query,
            resultSummary = "fetched ${paper.id.value}",
            egress = true,
        )
    }

    private fun okImport(
        call: ToolCall,
        paperId: String,
        imported: Boolean,
        already: Boolean,
        title: String?,
    ): ToolExecution {
        val body = json.encodeToString(ImportResult.serializer(), ImportResult(imported, already, title))
        val summary = if (already) "already in library" else "imported to library"
        return ToolExecution(
            result = ToolResult(call.id, call.name, body, isError = false),
            query = paperId,
            resultSummary = summary,
            egress = true,
        )
    }

    private fun errorResult(
        call: ToolCall,
        query: String,
        message: String,
        egress: Boolean,
    ): ToolExecution =
        ToolExecution(
            result = ToolResult(call.id, call.name, """{"error":"$message"}""", isError = true),
            query = query,
            resultSummary = message,
            egress = egress,
        )

    // --- arg parsing (dotted paths reach nested objects like `date_range.from`) ---

    private fun parseArgs(call: ToolCall): JsonObject? =
        runCatching { json.parseToJsonElement(call.inputJson).jsonObject }.getOrNull()

    private fun JsonObject.primitiveAt(path: String): JsonPrimitive? {
        var cur: JsonElement = this
        for (part in path.split(".")) {
            cur = (cur as? JsonObject)?.get(part) ?: return null
        }
        return cur as? JsonPrimitive
    }

    private fun JsonObject.stringAt(path: String): String? = primitiveAt(path)?.contentOrNull

    private fun JsonObject.intAt(path: String): Int? = primitiveAt(path)?.intOrNull

    /** chemRxiv `publishedDate` → Instant: an ISO instant first, then a bare date; absent/unparseable → EPOCH. */
    private fun String?.toInstantOrEpoch(): Instant =
        this?.let {
            runCatching { Instant.parse(it) }.getOrNull()
                ?: runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
        } ?: Instant.EPOCH

    companion object {
        const val NAME = "search_my_library"
        const val SEARCH_ARXIV_NAME = "search_arxiv"
        const val GET_PAPER_NAME = "get_paper"
        const val IMPORT_NAME = "import_to_library"
        const val SEARCH_SEMANTIC_SCHOLAR_NAME = "search_semantic_scholar"
        const val SEARCH_CHEMRXIV_NAME = "search_chemrxiv"

        private const val DEFAULT_K = 6
        private const val MAX_K = 8
        private const val DEFAULT_ARXIV_RESULTS = 8
        private const val MAX_ARXIV_RESULTS = 25
        private const val DEFAULT_S2_RESULTS = 8
        private const val MAX_S2_RESULTS = 25
        private const val DEFAULT_CHEM_RESULTS = 8
        private const val MAX_CHEM_RESULTS = 25

        /**
         * Per-leg candidate pool searched BEFORE the library filter. Generous because the leg scans the
         * whole cached `papers` corpus (a superset of the library); a wide pool makes it very unlikely a
         * relevant LIBRARY paper is truncated out before the library restriction. (A fully library-scoped
         * DAO search is a recorded follow-up for very large corpora.)
         */
        private const val CANDIDATE_LIMIT = 200
        private const val ABSTRACT_SNIPPET_CHARS = 320

        /** Cap on the search→import chemRxiv draft LRU (public metadata only; a miss re-searches). */
        private const val CHEM_DRAFT_CACHE_SIZE = 64

        private const val CITE_HINT =
            "Cite any paper you use in prose as arXiv:<id> — do NOT use bracketed [n] citations for these."

        val SEARCH_MY_LIBRARY =
            ToolDef(
                name = NAME,
                description =
                    "Search the user's own saved arXiv library (on-device) for papers relevant to a query. " +
                        "Returns matching saved papers with a title + abstract snippet. $CITE_HINT",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "query",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Natural-language search over the user's saved library.")
                                    },
                                )
                                put(
                                    "k",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Max results (default 6, capped 8).")
                                    },
                                )
                            },
                        )
                        put("required", buildJsonArray { add("query") })
                    },
            )

        val SEARCH_ARXIV =
            ToolDef(
                name = SEARCH_ARXIV_NAME,
                description =
                    "Search the arXiv preprint server (live). Your query text is sent to arxiv.org, a third " +
                        "party. Returns public paper metadata (title, authors, abstract snippet, category). $CITE_HINT",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "query",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "Search terms; arXiv field syntax (ti:, cat:) passes through.",
                                        )
                                    },
                                )
                                put(
                                    "category",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional arXiv category to filter by, e.g. cs.LG.")
                                    },
                                )
                                put(
                                    "date_range",
                                    buildJsonObject {
                                        put("type", "object")
                                        put(
                                            "properties",
                                            buildJsonObject {
                                                put(
                                                    "from",
                                                    buildJsonObject {
                                                        put("type", "string")
                                                        put(
                                                            "description",
                                                            "Inclusive lower bound, ISO date YYYY-MM-DD.",
                                                        )
                                                    },
                                                )
                                                put(
                                                    "to",
                                                    buildJsonObject {
                                                        put("type", "string")
                                                        put(
                                                            "description",
                                                            "Inclusive upper bound, ISO date YYYY-MM-DD.",
                                                        )
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                                put(
                                    "max_results",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Max results (default 8, capped 25).")
                                    },
                                )
                            },
                        )
                        put("required", buildJsonArray { add("query") })
                    },
            )

        val GET_PAPER =
            ToolDef(
                name = GET_PAPER_NAME,
                description =
                    "Fetch one arXiv paper's full metadata (incl. the complete abstract) by id. The id is sent " +
                        "to arxiv.org, a third party. $CITE_HINT",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "arxiv_id",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "arXiv id, e.g. 2403.01234 (a version suffix is ignored).")
                                    },
                                )
                            },
                        )
                        put("required", buildJsonArray { add("arxiv_id") })
                    },
            )

        val IMPORT_TO_LIBRARY =
            ToolDef(
                name = IMPORT_NAME,
                description =
                    "Save a paper to the user's on-device library. Default (arXiv): pass `arxiv_id` — its " +
                        "metadata is fetched from arxiv.org (a third party) if not already cached. To save a " +
                        "chemRxiv hit from search_chemrxiv, pass `source:\"chemrxiv\"` and the hit's `doi` (only " +
                        "hits with `importable:true` can be saved; no extra fetch — the search result is reused). " +
                        "Idempotent: a paper already in the library is not re-added.",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "arxiv_id",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "arXiv id to save, e.g. 2403.01234 (arXiv imports).")
                                    },
                                )
                                put(
                                    "source",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "Set to \"chemrxiv\" to save a chemRxiv hit; omit for arXiv.",
                                        )
                                    },
                                )
                                put(
                                    "doi",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "The chemRxiv hit's DOI (required when source=chemrxiv).")
                                    },
                                )
                            },
                        )
                        // No top-level `required`: arXiv needs `arxiv_id`, chemRxiv needs `source`+`doi` — the
                        // per-branch handlers validate. A blanket `required:[arxiv_id]` would misdescribe chemRxiv.
                    },
            )

        val SEARCH_SEMANTIC_SCHOLAR =
            ToolDef(
                name = SEARCH_SEMANTIC_SCHOLAR_NAME,
                description =
                    "Search Semantic Scholar (all fields of study, incl. non-arXiv papers). Your query is sent " +
                        "to api.semanticscholar.org, a third party. Returns public metadata (title, abstract/tldr, " +
                        "authors, venue, year, citationCount, externalIds). Only hits with `importable:true` (an " +
                        "arXiv id) can be saved via import_to_library. $CITE_HINT",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "query",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Free-text search over the Semantic Scholar corpus.")
                                    },
                                )
                                put(
                                    "limit",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Max results (default 8, capped 25).")
                                    },
                                )
                                put(
                                    "venue",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional venue/journal to filter by.")
                                    },
                                )
                                put(
                                    "year",
                                    buildJsonObject {
                                        put("type", "object")
                                        put(
                                            "properties",
                                            buildJsonObject {
                                                put(
                                                    "from",
                                                    buildJsonObject {
                                                        put("type", "integer")
                                                        put("description", "Inclusive lower publication year.")
                                                    },
                                                )
                                                put(
                                                    "to",
                                                    buildJsonObject {
                                                        put("type", "integer")
                                                        put("description", "Inclusive upper publication year.")
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )
                        put("required", buildJsonArray { add("query") })
                    },
            )

        val SEARCH_CHEMRXIV =
            ToolDef(
                name = SEARCH_CHEMRXIV_NAME,
                description =
                    "Search chemRxiv (Cambridge Open Engage), the chemistry preprint server. Your query is " +
                        "sent to chemrxiv.org, a third party. Returns public metadata (title, abstract, DOI, " +
                        "authors, date, PDF link). These are DOI-keyed preprints, NOT arXiv. A hit with " +
                        "`importable:true` can be saved with import_to_library using `source:\"chemrxiv\"` and " +
                        "the hit's `doi`. $CITE_HINT",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "term",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Free-text search over the chemRxiv corpus.")
                                    },
                                )
                                put(
                                    "limit",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Max results (default 8, capped 25).")
                                    },
                                )
                                put(
                                    "skip",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Results to skip, for pagination (default 0).")
                                    },
                                )
                            },
                        )
                        put("required", buildJsonArray { add("term") })
                    },
            )
    }
}
