package dev.blokz.arxiver.data.tool

import dev.blokz.arxiver.core.ai.ToolCall
import dev.blokz.arxiver.core.ai.ToolDef
import dev.blokz.arxiver.core.ai.ToolResult
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.database.fts.KeywordHit
import dev.blokz.arxiver.core.search.HybridFusion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

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

/**
 * The P-Tools tool catalog + executor (PT.1). Currently ONE tool: `search_my_library` runs the
 * on-device paper-level hybrid search over the user's saved library and returns matching papers for
 * the cloud model to reason over.
 *
 * **Zero-egress by construction:** the collaborators are injected as pure functional seams (mirroring
 * `ChatRepository`'s `embedQuery` — which also keeps CI ONNX-free). This class imports NO `:core:*`
 * search/db/ml implementation and NO `:core:network` at all, so it cannot reach a third party or the
 * arXiv limiter. Only public paper abstracts return (never private notes — the [ToolContext.includeNotes]
 * gate is threaded to [keywordSearch]). The [semanticSearch] seam returns null when the embedding model
 * isn't ready (the DI wiring never triggers a download) → the result is flagged `degraded` (keyword-only).
 * `execute` never throws — a failure becomes an error result the model can recover from.
 */
class ToolRegistry(
    /** Keyword (BM25) leg over a wide candidate pool, notes-gated by the 2nd arg — private notes never
     *  influence a cloud turn. The caller restricts the pool to the library BEFORE fusion (see [search]). */
    private val keywordSearch: suspend (query: String, includeNotes: Boolean, limit: Int) -> List<KeywordHit>,
    /** Semantic (embedding) leg → (paperId, similarity); **null when the model isn't ready / embed failed**. */
    private val semanticSearch: suspend (query: String, k: Int) -> List<Pair<String, Double>>?,
    /** The ids of papers in the user's saved library — the `inLibrary` filter. */
    private val libraryPaperIds: suspend () -> Set<String>,
    /** Hydrate papers for semantic-only hits the keyword leg didn't already return. */
    private val paperById: suspend (ids: List<String>) -> List<PaperEntity>,
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override fun toolDefs(): List<ToolDef> = listOf(SEARCH_MY_LIBRARY)

    override suspend fun execute(
        call: ToolCall,
        context: ToolContext,
    ): ToolExecution {
        if (call.name != NAME) return errorResult(call, "", "unknown tool")
        val args = runCatching { json.parseToJsonElement(call.inputJson).jsonObject }.getOrNull()
        val query = (args?.get("query") as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        if (query.isBlank()) return errorResult(call, query, "empty query")
        val k = ((args?.get("k") as? JsonPrimitive)?.intOrNull ?: DEFAULT_K).coerceIn(1, MAX_K)
        // execute MUST NOT throw — a DB failure becomes an error result the model can recover from.
        return runCatching { search(context, call, query, k) }
            .getOrElse { errorResult(call, query, "search failed") }
    }

    private suspend fun search(
        context: ToolContext,
        call: ToolCall,
        query: String,
        k: Int,
    ): ToolExecution {
        val librarySet = libraryPaperIds()
        if (librarySet.isEmpty()) return okResult(call, query, degraded = false, hits = emptyList())

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
        return okResult(call, query, degraded, hits)
    }

    private fun okResult(
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

    private fun errorResult(
        call: ToolCall,
        query: String,
        message: String,
    ): ToolExecution =
        ToolExecution(
            result = ToolResult(call.id, call.name, """{"error":"$message"}""", isError = true),
            query = query,
            resultSummary = message,
            egress = false,
        )

    companion object {
        const val NAME = "search_my_library"
        private const val DEFAULT_K = 6
        private const val MAX_K = 8

        /**
         * Per-leg candidate pool searched BEFORE the library filter. Generous because the leg scans the
         * whole cached `papers` corpus (a superset of the library); a wide pool makes it very unlikely a
         * relevant LIBRARY paper is truncated out before the library restriction. (A fully library-scoped
         * DAO search is a recorded follow-up for very large corpora.)
         */
        private const val CANDIDATE_LIMIT = 200
        private const val ABSTRACT_SNIPPET_CHARS = 320

        val SEARCH_MY_LIBRARY =
            ToolDef(
                name = NAME,
                description =
                    "Search the user's own saved arXiv library (on-device) for papers relevant to a query. " +
                        "Returns matching saved papers with a title + abstract snippet. Cite any paper you use " +
                        "in prose as arXiv:<id> — do NOT use bracketed [n] citations for these.",
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
    }
}
