package dev.blokz.arxiver.data.tool

import dev.blokz.arxiver.core.ai.ToolCall
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.database.fts.KeywordHit
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.network.arxiv.SearchFilter
import dev.blokz.arxiver.core.network.chemrxiv.ChemRxivAsset
import dev.blokz.arxiver.core.network.chemrxiv.ChemRxivAssetOriginal
import dev.blokz.arxiver.core.network.chemrxiv.ChemRxivAuthor
import dev.blokz.arxiver.core.network.chemrxiv.ChemRxivItem
import dev.blokz.arxiver.core.network.chemrxiv.ChemRxivItemHit
import dev.blokz.arxiver.core.network.chemrxiv.ChemRxivSearchResponse
import dev.blokz.arxiver.core.network.s2.S2Author
import dev.blokz.arxiver.core.network.s2.S2ExternalIds
import dev.blokz.arxiver.core.network.s2.S2OpenAccessPdf
import dev.blokz.arxiver.core.network.s2.S2SearchPaper
import dev.blokz.arxiver.core.network.s2.S2SearchResponse
import dev.blokz.arxiver.core.network.s2.S2Tldr
import dev.blokz.arxiver.data.PaperPage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM proof of `search_my_library` (P-Tools PT.1) via fake seams (mirrors `ChatRepository`'s
 * `embedQuery` seam — keeps CI ONNX-free). Covers the red lines: notes-gate passthrough, library-only
 * filter, zero egress on every path, degrade when the embed model is unavailable, never-throws.
 * Test scores are chosen so hits survive `HybridFusion`'s min-max normalize + 0.70 quality gate.
 */
class ToolRegistryTest {
    private val json = Json { ignoreUnknownKeys = true }

    // Default context: notes on, external OFF (library-only) — external tests pass [extCtx] explicitly.
    private val ctx = ToolContext(includeNotes = true, externalEnabled = false)
    private val extCtx = ToolContext(includeNotes = true, externalEnabled = true)

    private fun paper(id: String) =
        PaperEntity(
            id = id, latestVersion = 1, title = "T $id", abstract = "Abstract of $id",
            publishedAt = 0, updatedAt = 0, primaryCategory = "cs.LG", authorsLine = "X",
            comment = null, journalRef = null, doi = null, pdfUrl = "", citationCount = null,
            s2PaperId = null, source = "MANUAL", fetchedAt = 0, embeddedAt = null, citationsSyncedAt = null,
        )

    /** A domain [Paper] for the external-tool fakes (search_arxiv / get_paper / import). */
    private fun domainPaper(id: String) =
        Paper(
            id = ArxivId(id),
            latestVersion = 1,
            title = "T $id",
            abstract = "Abstract of $id",
            publishedAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            primaryCategory = "cs.LG",
            categories = listOf("cs.LG"),
            authors = listOf("A. Author"),
        )

    private fun registry(
        keyword: List<KeywordHit> = emptyList(),
        semantic: List<Pair<String, Double>>? = null,
        library: Set<String> = keyword.map { it.paper.id }.toSet(),
        keywordSpy: ((Boolean) -> Unit)? = null,
        // PT.2 external seams — defaulted no-ops so the PT.1 tests are unaffected. [savedIds] models the
        // library table shared by savePaper (inserts) and isInLibrary (membership).
        searchArxiv: suspend (SearchFilter, Int) -> AppResult<PaperPage> = { _, _ ->
            AppResult.Success(PaperPage(emptyList(), 0, null))
        },
        getPaper: suspend (ArxivId) -> Paper? = { null },
        savedIds: MutableSet<String> = mutableSetOf(),
        // PT.3 seam — defaulted no-op so PT.1/PT.2 tests are unaffected.
        searchSemanticScholar: suspend (String, Int, String?, Int?, Int?) -> AppResult<S2SearchResponse> =
            { _, _, _, _, _ -> AppResult.Success(S2SearchResponse()) },
        // PT.4 seam — defaulted no-op.
        searchChemRxiv: suspend (String, Int, Int) -> AppResult<ChemRxivSearchResponse> =
            { _, _, _ -> AppResult.Success(ChemRxivSearchResponse()) },
    ) = ToolRegistry(
        keywordSearch = { _, notes, _ ->
            keywordSpy?.invoke(notes)
            keyword
        },
        semanticSearch = { _, _ -> semantic },
        libraryPaperIds = { library },
        paperById = { ids -> ids.map { paper(it) } },
        searchArxiv = searchArxiv,
        getPaper = getPaper,
        savePaper = { id -> savedIds.add(id) },
        isInLibrary = { id -> id in savedIds },
        searchSemanticScholar = searchSemanticScholar,
        searchChemRxiv = searchChemRxiv,
    )

    private fun exec(
        reg: ToolRegistry,
        input: String,
        context: ToolContext = ctx,
        tool: String = ToolRegistry.NAME,
    ) = runBlocking { reg.execute(ToolCall("t1", tool, input), context) }

    private fun body(json: String) = this.json.parseToJsonElement(json).jsonObject

    private fun ids(bodyJson: String) =
        body(bodyJson)["hits"]!!.jsonArray.map { it.jsonObject["paperId"]!!.jsonPrimitive.content }

    @Test
    fun `a hybrid hit returns the citation-keyed shape, provenance BOTH, non-degraded, non-egress`() {
        val r =
            exec(
                registry(keyword = listOf(KeywordHit(paper("2401.1"), 1.0)), semantic = listOf("2401.1" to 0.9)),
                """{"query":"diffusion"}""",
            )
        assertFalse(r.result.isError)
        assertFalse(r.egress)
        val obj = body(r.result.contentJson)
        assertEquals(false, obj["degraded"]!!.jsonPrimitive.boolean)
        val hit = obj["hits"]!!.jsonArray.single().jsonObject
        assertEquals("2401.1", hit["paperId"]!!.jsonPrimitive.content)
        assertEquals(true, hit["inLibrary"]!!.jsonPrimitive.boolean)
        assertEquals("BOTH", hit["provenance"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unavailable semantic leg degrades to keyword-only`() {
        val r = exec(registry(keyword = listOf(KeywordHit(paper("2401.1"), 1.0)), semantic = null), """{"query":"x"}""")
        assertEquals(true, body(r.result.contentJson)["degraded"]!!.jsonPrimitive.boolean)
        assertTrue(r.resultSummary.contains("keyword-only"))
        assertEquals(listOf("2401.1"), ids(r.result.contentJson))
    }

    @Test
    fun `includeNotes is threaded to the keyword search (red line)`() {
        var seen: Boolean? = null
        val reg = registry(keyword = listOf(KeywordHit(paper("2401.1"), 1.0)), keywordSpy = { seen = it })
        exec(reg, """{"query":"x"}""", ToolContext(includeNotes = false, externalEnabled = false))
        assertEquals(false, seen, "a cloud library search must not let notes influence ranking")
    }

    @Test
    fun `hits outside the library are filtered out`() {
        // Equal keyword scores ⇒ both survive normalization + the quality gate; the library filter
        // (not the gate) is what drops the non-library paper.
        val r =
            exec(
                registry(
                    keyword = listOf(KeywordHit(paper("2401.1"), 1.0), KeywordHit(paper("gone.9"), 1.0)),
                    library = setOf("2401.1"),
                ),
                """{"query":"x"}""",
            )
        assertEquals(listOf("2401.1"), ids(r.result.contentJson))
    }

    @Test
    fun `library hits survive when non-library papers out-rank and crowd them (F1 regression)`() {
        // Non-library papers score much HIGHER on both legs — over the whole corpus they'd win the
        // 0.70 quality gate + fill resultLimit, dropping the library match to ZERO. Both legs must be
        // restricted to the library BEFORE fusion, so the single library paper still returns.
        val libPaper = KeywordHit(paper("2401.lib"), 0.3)
        val nonLibKeyword = (1..25).map { KeywordHit(paper("browse.$it"), 9.0) }
        val nonLibSemantic = (1..25).map { "browse.$it" to 0.99 }
        val reg =
            registry(
                keyword = nonLibKeyword + libPaper,
                semantic = nonLibSemantic,
                library = setOf("2401.lib"),
            )
        val r = exec(reg, """{"query":"x"}""")
        assertEquals(listOf("2401.lib"), ids(r.result.contentJson), "the library match must not be crowded out")
    }

    @Test
    fun `an empty library returns no hits and is not degraded`() {
        val obj = body(exec(registry(library = emptySet()), """{"query":"x"}""").result.contentJson)
        assertEquals(false, obj["degraded"]!!.jsonPrimitive.boolean)
        assertTrue(obj["hits"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `k is clamped to at most 8`() {
        val many = (1..20).map { KeywordHit(paper("2401.$it"), 1.0) } // equal scores all survive the gate
        val r = exec(registry(keyword = many, library = many.map { it.paper.id }.toSet()), """{"query":"x","k":50}""")
        assertTrue(body(r.result.contentJson)["hits"]!!.jsonArray.size <= 8)
    }

    @Test
    fun `a blank query, malformed input, and unknown tool are error results — never thrown`() {
        assertTrue(exec(registry(), """{"query":"   "}""").result.isError)
        assertTrue(exec(registry(), "not json").result.isError)
        assertTrue(
            runBlocking { registry().execute(ToolCall("t", "unknown_tool", "{}"), ctx) }.result.isError,
        )
    }

    @Test
    fun `a throwing seam is caught and returned as an error (execute never throws)`() {
        val reg =
            ToolRegistry(
                keywordSearch = { _, _, _ -> throw RuntimeException("db down") },
                semanticSearch = { _, _ -> null },
                libraryPaperIds = { setOf("2401.1") },
                paperById = { emptyList() },
                searchArxiv = { _, _ -> AppResult.Success(PaperPage(emptyList(), 0, null)) },
                getPaper = { null },
                savePaper = { },
                isInLibrary = { false },
                searchSemanticScholar = { _, _, _, _, _ -> AppResult.Success(S2SearchResponse()) },
                searchChemRxiv = { _, _, _ -> AppResult.Success(ChemRxivSearchResponse()) },
            )
        val r = exec(reg, """{"query":"x"}""")
        assertTrue(r.result.isError)
        assertFalse(r.egress)
    }

    @Test
    fun `every path is non-egress`() {
        assertFalse(exec(registry(keyword = listOf(KeywordHit(paper("2401.1"), 1.0))), """{"query":"x"}""").egress)
        assertFalse(exec(registry(), "bad").egress)
        assertFalse(exec(registry(library = emptySet()), """{"query":"x"}""").egress)
    }

    // --- PT.2: external tools (search_arxiv / get_paper / import_to_library) ---

    @Test
    fun `toolDefs offers exactly the consented subset by class`() {
        val reg = registry()
        assertEquals(
            listOf("search_my_library"),
            reg.toolDefs(ToolConsent(library = true, external = false)).map { it.name },
        )
        assertEquals(
            setOf("search_arxiv", "get_paper", "import_to_library", "search_semantic_scholar", "search_chemrxiv"),
            reg.toolDefs(ToolConsent(library = false, external = true)).map { it.name }.toSet(),
        )
        assertEquals(6, reg.toolDefs(ToolConsent(library = true, external = true)).size)
        assertTrue(reg.toolDefs(ToolConsent.NONE).isEmpty())
    }

    @Test
    fun `an external call in a web-off turn is refused before the seam runs (red line)`() {
        var searched = false
        val reg =
            registry(
                searchArxiv = { _, _ ->
                    searched = true
                    AppResult.Success(PaperPage(emptyList(), 0, null))
                },
            )
        // externalEnabled=false ⇒ the execute-time guard rejects a hallucinated external call.
        val r = exec(reg, """{"query":"x"}""", ctx, tool = ToolRegistry.SEARCH_ARXIV_NAME)
        assertTrue(r.result.isError)
        assertTrue(r.egress, "an attempted external call is disclosed as egress-class")
        assertFalse(searched, "the seam must NOT run without web consent — nothing egresses")
    }

    @Test
    fun `every external path is egress incl error paths, while library stays non-egress`() {
        // blank query
        assertTrue(exec(registry(), """{"query":"  "}""", extCtx, ToolRegistry.SEARCH_ARXIV_NAME).egress)
        // failed search
        val failReg = registry(searchArxiv = { _, _ -> AppResult.Failure(AppError.Offline) })
        assertTrue(exec(failReg, """{"query":"x"}""", extCtx, ToolRegistry.SEARCH_ARXIV_NAME).egress)
        // bad id (get_paper)
        assertTrue(exec(registry(), """{"arxiv_id":"nope"}""", extCtx, ToolRegistry.GET_PAPER_NAME).egress)
        // not-enabled guard
        assertTrue(exec(registry(), """{"query":"x"}""", ctx, ToolRegistry.SEARCH_ARXIV_NAME).egress)
        // library stays non-egress
        assertFalse(exec(registry(), """{"query":"x"}""").egress)
    }

    @Test
    fun `search_arxiv returns arxiv hits from the repository`() {
        val papers = listOf(domainPaper("2401.11111"), domainPaper("2401.22222"))
        val reg = registry(searchArxiv = { _, _ -> AppResult.Success(PaperPage(papers, 2, null)) })
        val r = exec(reg, """{"query":"diffusion"}""", extCtx, ToolRegistry.SEARCH_ARXIV_NAME)
        assertFalse(r.result.isError)
        assertTrue(r.egress)
        val hits = body(r.result.contentJson)["hits"]!!.jsonArray
        assertEquals(2, hits.size)
        assertEquals("2401.11111", hits.first().jsonObject["arxivId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `search_arxiv clamps max_results and never throws on a malformed date_range`() {
        var capturedMax = 0
        val reg =
            registry(
                searchArxiv = { _, max ->
                    capturedMax = max
                    AppResult.Success(PaperPage(emptyList(), 0, null))
                },
            )
        val r =
            exec(
                reg,
                """{"query":"x","max_results":9999,"date_range":{"from":"not-a-date"}}""",
                extCtx,
                ToolRegistry.SEARCH_ARXIV_NAME,
            )
        assertFalse(r.result.isError, "a bad date is treated as absent, never thrown")
        assertEquals(25, capturedMax, "max_results is clamped to 25")
    }

    @Test
    fun `get_paper parses a versioned id to the un-versioned cache key (reader hit)`() {
        var requested: String? = null
        val reg =
            registry(
                getPaper = { id ->
                    requested = id.value
                    domainPaper(id.value)
                },
            )
        val r = exec(reg, """{"arxiv_id":"2403.01234v2"}""", extCtx, ToolRegistry.GET_PAPER_NAME)
        assertFalse(r.result.isError)
        assertEquals("2403.01234", requested, "a versioned input must resolve to the bare id")
        assertEquals("2403.01234", body(r.result.contentJson)["arxivId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `import persists the papers row before saving, is idempotent, and skips already-saved ids`() {
        val fetched = mutableListOf<String>()
        val saved = mutableSetOf<String>()
        val reg =
            registry(
                getPaper = { id ->
                    fetched += id.value
                    domainPaper(id.value)
                },
                savedIds = saved,
            )
        // first import of an id not in the library: fetch (papers-row persist) THEN save.
        val r1 = exec(reg, """{"arxiv_id":"2401.00001"}""", extCtx, ToolRegistry.IMPORT_NAME)
        val b1 = body(r1.result.contentJson)
        assertEquals(true, b1["imported"]!!.jsonPrimitive.boolean)
        assertEquals(false, b1["alreadyInLibrary"]!!.jsonPrimitive.boolean)
        assertTrue("2401.00001" in saved, "the library row was written")
        assertEquals(listOf("2401.00001"), fetched, "getPaper (FK papers-row) ran before save")
        // re-import: short-circuits on isInLibrary — no fetch, marked alreadyInLibrary.
        val r2 = exec(reg, """{"arxiv_id":"2401.00001"}""", extCtx, ToolRegistry.IMPORT_NAME)
        val b2 = body(r2.result.contentJson)
        assertEquals(false, b2["imported"]!!.jsonPrimitive.boolean)
        assertEquals(true, b2["alreadyInLibrary"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("2401.00001"), fetched, "a re-import must not fetch again")
    }

    @Test
    fun `an unknown tool and malformed external input are error results, never thrown`() {
        assertTrue(exec(registry(), "{}", extCtx, "no_such_tool").result.isError)
        assertTrue(exec(registry(), "not json", extCtx, ToolRegistry.SEARCH_ARXIV_NAME).result.isError)
        assertTrue(exec(registry(), """{"arxiv_id":"###"}""", extCtx, ToolRegistry.IMPORT_NAME).result.isError)
    }

    // --- PT.3: search_semantic_scholar (EXTERNAL) ---

    private fun s2Paper(
        title: String,
        arxiv: String? = null,
    ) = S2SearchPaper(
        paperId = "s2:$title",
        title = title,
        abstract = "Abstract of $title",
        tldr = S2Tldr(text = "tldr of $title"),
        externalIds = S2ExternalIds(ArXiv = arxiv, DOI = "10.1/$title"),
        venue = "NeurIPS",
        year = 2024,
        authors = listOf(S2Author(name = "A. Author")),
        citationCount = 42,
        openAccessPdf = S2OpenAccessPdf(url = "https://x/$title.pdf"),
    )

    @Test
    fun `search_semantic_scholar maps hits and flags importable only for arXiv-keyed results`() {
        val reg =
            registry(
                searchSemanticScholar = { _, _, _, _, _ ->
                    AppResult.Success(S2SearchResponse(data = listOf(s2Paper("A", arxiv = "2401.00001"), s2Paper("B"))))
                },
            )
        val r = exec(reg, """{"query":"diffusion"}""", extCtx, ToolRegistry.SEARCH_SEMANTIC_SCHOLAR_NAME)
        assertFalse(r.result.isError)
        assertTrue(r.egress)
        val hits = body(r.result.contentJson)["hits"]!!.jsonArray
        assertEquals(2, hits.size)
        // Hit A carries an arXiv id → importable; hit B does not → read-only (non-arXiv import is out of scope).
        assertEquals(true, hits[0].jsonObject["importable"]!!.jsonPrimitive.boolean)
        assertEquals(false, hits[1].jsonObject["importable"]!!.jsonPrimitive.boolean)
        assertEquals("tldr of A", hits[0].jsonObject["tldr"]!!.jsonPrimitive.content)
    }

    @Test
    fun `search_semantic_scholar is refused in a web-off turn before the seam runs`() {
        var searched = false
        val reg =
            registry(
                searchSemanticScholar = { _, _, _, _, _ ->
                    searched = true
                    AppResult.Success(S2SearchResponse())
                },
            )
        val r = exec(reg, """{"query":"x"}""", ctx, ToolRegistry.SEARCH_SEMANTIC_SCHOLAR_NAME)
        assertTrue(r.result.isError)
        assertTrue(r.egress, "an attempted external call is disclosed as egress-class")
        assertFalse(searched, "the S2 seam must NOT run without web consent")
    }

    @Test
    fun `every search_semantic_scholar path is egress incl error paths`() {
        // success
        assertTrue(exec(registry(), """{"query":"x"}""", extCtx, ToolRegistry.SEARCH_SEMANTIC_SCHOLAR_NAME).egress)
        // blank query
        assertTrue(exec(registry(), """{"query":"  "}""", extCtx, ToolRegistry.SEARCH_SEMANTIC_SCHOLAR_NAME).egress)
        // upstream failure (e.g. a 429)
        val failReg = registry(searchSemanticScholar = { _, _, _, _, _ -> AppResult.Failure(AppError.Upstream(429)) })
        val r = exec(failReg, """{"query":"x"}""", extCtx, ToolRegistry.SEARCH_SEMANTIC_SCHOLAR_NAME)
        assertTrue(r.result.isError)
        assertTrue(r.egress)
    }

    // --- PT.4: search_chemrxiv (EXTERNAL) ---

    private fun chemHit(
        title: String,
        name: String? = null,
        firstLast: Pair<String, String>? = null,
        pdfUrl: String? = null,
        assetUrl: String? = null,
    ) = ChemRxivItemHit(
        item =
            ChemRxivItem(
                id = "cr:$title",
                title = title,
                abstract = "Abstract of $title",
                doi = "10.26434/$title",
                publishedDate = "2024-01-01T00:00:00Z",
                pdfUrl = pdfUrl,
                authors =
                    listOfNotNull(
                        name?.let { ChemRxivAuthor(name = it) },
                        firstLast?.let { ChemRxivAuthor(firstName = it.first, lastName = it.second) },
                    ),
                asset = assetUrl?.let { ChemRxivAsset(original = ChemRxivAssetOriginal(url = it)) },
            ),
    )

    @Test
    fun `search_chemrxiv maps the itemHits-dot-item envelope incl both author shapes and pdf fallback`() {
        val reg =
            registry(
                searchChemRxiv = { _, _, _ ->
                    AppResult.Success(
                        ChemRxivSearchResponse(
                            totalCount = 2,
                            itemHits =
                                listOf(
                                    chemHit("A", name = "Ada Lovelace", pdfUrl = "https://chemrxiv.org/a.pdf"),
                                    chemHit(
                                        "B",
                                        firstLast = "Alan" to "Turing",
                                        assetUrl = "https://chemrxiv.org/b.pdf",
                                    ),
                                ),
                        ),
                    )
                },
            )
        val r = exec(reg, """{"term":"catalysis"}""", extCtx, ToolRegistry.SEARCH_CHEMRXIV_NAME)
        assertFalse(r.result.isError)
        assertTrue(r.egress)
        val hits = body(r.result.contentJson)["hits"]!!.jsonArray
        assertEquals(2, hits.size, "the itemHits[].item nesting is unwrapped (a flat DTO would give 0)")
        assertEquals("A", hits[0].jsonObject["title"]!!.jsonPrimitive.content)
        // author `name` shape:
        assertEquals("Ada Lovelace", hits[0].jsonObject["authors"]!!.jsonArray.single().jsonPrimitive.content)
        // top-level pdfUrl wins:
        assertEquals("https://chemrxiv.org/a.pdf", hits[0].jsonObject["pdfUrl"]!!.jsonPrimitive.content)
        // firstName+lastName shape joins:
        assertEquals("Alan Turing", hits[1].jsonObject["authors"]!!.jsonArray.single().jsonPrimitive.content)
        // asset.original.url fallback when no top-level pdfUrl:
        assertEquals("https://chemrxiv.org/b.pdf", hits[1].jsonObject["pdfUrl"]!!.jsonPrimitive.content)
    }

    @Test
    fun `search_chemrxiv is refused in a web-off turn before the seam runs`() {
        var searched = false
        val reg =
            registry(
                searchChemRxiv = { _, _, _ ->
                    searched = true
                    AppResult.Success(ChemRxivSearchResponse())
                },
            )
        val r = exec(reg, """{"term":"x"}""", ctx, ToolRegistry.SEARCH_CHEMRXIV_NAME)
        assertTrue(r.result.isError)
        assertTrue(r.egress, "an attempted external call is disclosed as egress-class")
        assertFalse(searched, "the chemRxiv seam must NOT run without web consent")
    }

    @Test
    fun `every search_chemrxiv path is egress incl error paths`() {
        assertTrue(exec(registry(), """{"term":"x"}""", extCtx, ToolRegistry.SEARCH_CHEMRXIV_NAME).egress)
        assertTrue(exec(registry(), """{"term":"  "}""", extCtx, ToolRegistry.SEARCH_CHEMRXIV_NAME).egress)
        val failReg = registry(searchChemRxiv = { _, _, _ -> AppResult.Failure(AppError.Upstream(429)) })
        val r = exec(failReg, """{"term":"x"}""", extCtx, ToolRegistry.SEARCH_CHEMRXIV_NAME)
        assertTrue(r.result.isError)
        assertTrue(r.egress)
    }

    @Test
    fun `search_chemrxiv clamps limit and threads skip, never throwing`() {
        var capturedLimit = 0
        var capturedSkip = -1
        val reg =
            registry(
                searchChemRxiv = { _, limit, skip ->
                    capturedLimit = limit
                    capturedSkip = skip
                    AppResult.Success(ChemRxivSearchResponse())
                },
            )
        val r = exec(reg, """{"term":"x","limit":9999,"skip":20}""", extCtx, ToolRegistry.SEARCH_CHEMRXIV_NAME)
        assertFalse(r.result.isError)
        assertEquals(25, capturedLimit, "limit clamped to 25")
        assertEquals(20, capturedSkip, "skip threaded to the seam")
    }

    @Test
    fun `search_semantic_scholar clamps limit and threads the year window, never throwing`() {
        var capturedLimit = 0
        var capturedYear: Pair<Int?, Int?>? = null
        val reg =
            registry(
                searchSemanticScholar = { _, limit, _, from, to ->
                    capturedLimit = limit
                    capturedYear = from to to
                    AppResult.Success(S2SearchResponse())
                },
            )
        val r =
            exec(
                reg,
                """{"query":"x","limit":9999,"year":{"from":2019,"to":2023}}""",
                extCtx,
                ToolRegistry.SEARCH_SEMANTIC_SCHOLAR_NAME,
            )
        assertFalse(r.result.isError)
        assertEquals(25, capturedLimit, "limit is clamped to 25")
        assertEquals(2019 to 2023, capturedYear, "the year window is threaded to the seam")
    }
}
