package dev.blokz.arxiver.core.claude

import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.ExternalRef
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.Source
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PayloadBuilderTest {
    private val fixedNow = Instant.parse("2026-06-11T18:30:00Z")
    private val builder = PayloadBuilder(appVersion = "1.0.0", now = { fixedNow })

    private val paper =
        PaperWithAnnotations(
            paper =
                Paper(
                    ref = ArxivRef(ArxivId("2403.01234")),
                    latestVersion = 2,
                    title = "Efficient State Space Models",
                    abstract = "We study state space models.",
                    publishedAt = Instant.parse("2024-03-02T18:00:01Z"),
                    updatedAt = Instant.parse("2024-03-15T17:59:59Z"),
                    primaryCategory = "cs.LG",
                    categories = listOf("cs.LG", "stat.ML"),
                    authors = listOf("Ada Researcher", "Boris Scholar"),
                    doi = null,
                    citationCount = 87,
                    pdfUrl = "https://arxiv.org/pdf/2403.01234v2",
                ),
            tags = listOf("ssm"),
            status = "read",
            rating = 4,
            notes = listOf("Compare with S4."),
        )

    /** A non-arXiv (chemRxiv) paper — its `ref` is an [ExternalRef], so `paper.id` would `error()` (P-Dispatch). */
    private val chemPaper =
        PaperWithAnnotations(
            paper =
                Paper(
                    ref = ExternalRef(Source.CHEMRXIV, "10.26434/chemrxiv-2024-xyz"),
                    latestVersion = 1,
                    title = "A Chemistry Preprint",
                    abstract = "We study a reaction.",
                    publishedAt = Instant.parse("2024-05-01T09:00:00Z"),
                    updatedAt = Instant.parse("2024-05-01T09:00:00Z"),
                    primaryCategory = "",
                    categories = emptyList(),
                    authors = listOf("Marie Curie"),
                    doi = "10.26434/chemrxiv-2024-xyz",
                    citationCount = null,
                    pdfUrl = "https://chemrxiv.org/engage/chemrxiv/assets/xyz.pdf",
                ),
            tags = listOf("catalysis"),
            status = "to_read",
            rating = null,
            notes = listOf("secret chem note"),
        )

    private val relations =
        PayloadRelations(
            similarity = listOf(PayloadSimilarityEdge(a = "2403.01234", b = "2405.06789", cosine = 0.831)),
            citations = listOf(PayloadCitationEdge(citing = "2405.06789", cited = "2403.01234")),
            libraryNeighbors =
                listOf(
                    PayloadNeighbor(
                        arxivId = "2402.00007",
                        near = "2403.01234",
                        title = "Neighboring Work",
                        cosine = 0.792,
                        inLibrary = true,
                        absUrl = "https://arxiv.org/abs/2402.00007v1",
                    ),
                ),
        )

    /** Golden contract test (SPEC-CLAUDE-BRIDGE §4) — routine authors rely on this shape. */
    @Test
    fun `digest payload matches the documented contract`() {
        val result =
            builder.build(
                action = RoutineAction.DIGEST,
                instruction = "Digest these papers.",
                papers = listOf(paper),
                includeNotes = true,
                librarySize = 412,
            )

        val ready = assertIs<PayloadResult.Ready>(result)
        val root = Json.parseToJsonElement(ready.json).jsonObject

        assertEquals("arxiver/v1", root.str("schema"))
        assertEquals("1.0.0", root.str("app_version"))
        assertEquals("digest", root.str("action"))
        assertEquals("2026-06-11T18:30:00Z", root.str("sent_at"))
        assertEquals("Digest these papers.", root.str("instruction"))

        val context = root.getValue("context").jsonObject
        assertEquals("true", context.str("include_notes"))
        assertEquals("412", context.str("library_size"))
        assertEquals("ssm", context.getValue("user_tags_in_selection").jsonArray[0].jsonPrimitive.content)

        val p = root.getValue("papers").jsonArray[0].jsonObject
        assertEquals("2403.01234", p.str("arxiv_id"))
        assertEquals("2", p.str("version"))
        assertEquals("https://arxiv.org/abs/2403.01234v2", p.str("abs_url"))
        assertEquals("https://arxiv.org/pdf/2403.01234v2", p.str("pdf_url"))
        assertEquals("87", p.str("citation_count"))

        val user = p.getValue("user").jsonObject
        assertEquals("read", user.str("status"))
        assertEquals("4", user.str("rating"))
        assertEquals("Compare with S4.", user.getValue("notes").jsonArray[0].jsonPrimitive.content)
    }

    /** SPEC-CLAUDE-BRIDGE §7: structural redaction — no `user` keys anywhere. */
    @Test
    fun `notes off strips every user key structurally`() {
        val result =
            builder.build(
                action = RoutineAction.DIGEST,
                instruction = "x",
                papers = listOf(paper, paper.copy(notes = listOf("secret thought"))),
                includeNotes = false,
                librarySize = 1,
            )
        val ready = assertIs<PayloadResult.Ready>(result)
        assertTrue("\"user\"" !in ready.json)
        assertTrue("secret thought" !in ready.json)
        assertTrue("ssm" !in ready.json) // tags also redacted, incl. context aggregation
    }

    /** SPEC-CLAUDE-BRIDGE §4 `relations` — on-device primitives ride along. */
    @Test
    fun `relations block matches the documented contract`() {
        val result =
            builder.build(
                action = RoutineAction.COMPARE,
                instruction = "Compare.",
                papers = listOf(paper),
                includeNotes = true,
                librarySize = 412,
                relations = relations,
            )

        val ready = assertIs<PayloadResult.Ready>(result)
        val rel = Json.parseToJsonElement(ready.json).jsonObject.getValue("relations").jsonObject

        val sim = rel.getValue("similarity").jsonArray[0].jsonObject
        assertEquals("2403.01234", sim.str("a"))
        assertEquals("2405.06789", sim.str("b"))
        assertEquals("0.831", sim.str("cosine"))

        val cite = rel.getValue("citations").jsonArray[0].jsonObject
        assertEquals("2405.06789", cite.str("citing"))
        assertEquals("2403.01234", cite.str("cited"))

        val neighbor = rel.getValue("library_neighbors").jsonArray[0].jsonObject
        assertEquals("2402.00007", neighbor.str("arxiv_id"))
        assertEquals("2403.01234", neighbor.str("near"))
        assertEquals("Neighboring Work", neighbor.str("title"))
        assertEquals("true", neighbor.str("in_library"))
        assertEquals("https://arxiv.org/abs/2402.00007v1", neighbor.str("abs_url"))
    }

    /** Neighbors reveal the local corpus — they ride the notes privacy gate. */
    @Test
    fun `notes off strips library neighbors but keeps selection relations`() {
        val result =
            builder.build(
                action = RoutineAction.COMPARE,
                instruction = "x",
                papers = listOf(paper),
                includeNotes = false,
                librarySize = 1,
                relations = relations,
            )
        val ready = assertIs<PayloadResult.Ready>(result)
        assertTrue("\"library_neighbors\"" !in ready.json)
        assertTrue("Neighboring Work" !in ready.json)

        val rel = Json.parseToJsonElement(ready.json).jsonObject.getValue("relations").jsonObject
        assertEquals(1, rel.getValue("similarity").jsonArray.size)
        assertEquals(1, rel.getValue("citations").jsonArray.size)
    }

    @Test
    fun `empty relations are structurally absent`() {
        listOf(null, PayloadRelations()).forEach { empty ->
            val result =
                builder.build(
                    action = RoutineAction.DIGEST,
                    instruction = "x",
                    papers = listOf(paper),
                    includeNotes = true,
                    librarySize = 1,
                    relations = empty,
                )
            val ready = assertIs<PayloadResult.Ready>(result)
            assertTrue("\"relations\"" !in ready.json)
        }
    }

    @Test
    fun `oversized payload is refused`() {
        val huge =
            paper.copy(
                paper = paper.paper.copy(abstract = "x".repeat(300_000)),
            )
        val result = builder.build(RoutineAction.DIGEST, "x", listOf(huge), includeNotes = false, librarySize = 1)
        val tooLarge = assertIs<PayloadResult.TooLarge>(result)
        assertTrue(tooLarge.byteSize > tooLarge.limit)
    }

    @Test
    fun `ping payload carries no papers`() {
        val result = builder.build(RoutineAction.PING, "ping", emptyList(), includeNotes = false, librarySize = 0)
        val ready = assertIs<PayloadResult.Ready>(result)
        val root = Json.parseToJsonElement(ready.json).jsonObject
        assertEquals(0, root.getValue("papers").jsonArray.size)
        assertEquals("ping", root.str("action"))
    }

    @Test
    fun `every action has an instruction template`() {
        RoutineAction.entries.forEach { action ->
            // CUSTOM is intentionally empty; everything else must guide the routine.
            if (action != RoutineAction.CUSTOM) {
                assertTrue(PayloadBuilder.defaultInstruction(action).isNotBlank(), "missing template: $action")
            }
        }
    }

    /** P-Dispatch: a non-arXiv paper omits the arXiv keys and carries source identity + an honest fetchable flag. */
    @Test
    fun `a non-arXiv paper omits arxiv keys and carries source identity`() {
        val result = builder.build(RoutineAction.DIGEST, "x", listOf(chemPaper), includeNotes = true, librarySize = 1)
        val ready = assertIs<PayloadResult.Ready>(result)
        val p = Json.parseToJsonElement(ready.json).jsonObject.getValue("papers").jsonArray[0].jsonObject

        // arXiv-shaped keys are structurally ABSENT (null-tolerant access — str()/getValue would throw).
        assertTrue(p["arxiv_id"] == null, "no arxiv_id on a non-arXiv paper")
        assertTrue(p["version"] == null)
        assertTrue(p["abs_url"] == null)
        // Source identity present + honest un-fetchable signal.
        assertEquals("chemrxiv", p.str("source"))
        assertEquals("10.26434/chemrxiv-2024-xyz", p.str("native_id"))
        assertEquals("https://doi.org/10.26434/chemrxiv-2024-xyz", p.str("url"))
        assertEquals("false", p.str("pdf_fetchable"))
        assertEquals("https://chemrxiv.org/engage/chemrxiv/assets/xyz.pdf", p.str("pdf_url"))
    }

    /** P-Dispatch additivity proof: in a mixed selection the arXiv row stays byte-shaped; the non-arXiv row omits arXiv keys. */
    @Test
    fun `a mixed selection keeps the arXiv row byte-shaped and omits arxiv keys on the non-arXiv row`() {
        val result =
            builder.build(RoutineAction.DIGEST, "x", listOf(paper, chemPaper), includeNotes = false, librarySize = 2)
        val ready = assertIs<PayloadResult.Ready>(result)
        val papers = Json.parseToJsonElement(ready.json).jsonObject.getValue("papers").jsonArray

        val arxiv = papers[0].jsonObject
        assertEquals("2403.01234", arxiv.str("arxiv_id"))
        assertEquals("2", arxiv.str("version"))
        assertEquals("https://arxiv.org/abs/2403.01234v2", arxiv.str("abs_url"))
        assertTrue(arxiv["source"] == null, "an arXiv row never carries the source keys")
        assertTrue(arxiv["pdf_fetchable"] == null, "an arXiv row stays byte-identical (no new keys)")

        val chem = papers[1].jsonObject
        assertTrue(chem["arxiv_id"] == null)
        assertEquals("chemrxiv", chem.str("source"))
    }

    /** Red line: structural note/tag redaction holds with a non-arXiv paper in the mix. */
    @Test
    fun `notes off strips user keys even with a non-arXiv paper present`() {
        val result =
            builder.build(RoutineAction.DIGEST, "x", listOf(paper, chemPaper), includeNotes = false, librarySize = 2)
        val ready = assertIs<PayloadResult.Ready>(result)
        assertTrue("\"user\"" !in ready.json)
        assertTrue("secret chem note" !in ready.json)
        assertTrue("catalysis" !in ready.json)
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
}
