package dev.blokz.arxiver.data.tool

import dev.blokz.arxiver.core.ai.ToolCall
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.database.fts.KeywordHit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private val ctx = ToolContext(includeNotes = true)

    private fun paper(id: String) =
        PaperEntity(
            id = id, latestVersion = 1, title = "T $id", abstract = "Abstract of $id",
            publishedAt = 0, updatedAt = 0, primaryCategory = "cs.LG", authorsLine = "X",
            comment = null, journalRef = null, doi = null, pdfUrl = "", citationCount = null,
            s2PaperId = null, source = "MANUAL", fetchedAt = 0, embeddedAt = null, citationsSyncedAt = null,
        )

    private fun registry(
        keyword: List<KeywordHit> = emptyList(),
        semantic: List<Pair<String, Double>>? = null,
        library: Set<String> = keyword.map { it.paper.id }.toSet(),
        keywordSpy: ((Boolean) -> Unit)? = null,
    ) = ToolRegistry(
        keywordSearch = { _, notes ->
            keywordSpy?.invoke(notes)
            keyword
        },
        semanticSearch = { _, _ -> semantic },
        libraryPaperIds = { library },
        paperById = { ids -> ids.map { paper(it) } },
    )

    private fun exec(
        reg: ToolRegistry,
        input: String,
        context: ToolContext = ctx,
    ) = runBlocking { reg.execute(ToolCall("t1", ToolRegistry.NAME, input), context) }

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
        exec(reg, """{"query":"x"}""", ToolContext(includeNotes = false))
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
                keywordSearch = { _, _ -> throw RuntimeException("db down") },
                semanticSearch = { _, _ -> null },
                libraryPaperIds = { setOf("2401.1") },
                paperById = { emptyList() },
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
}
