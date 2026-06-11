package dev.blokz.arxiver.core.claude

import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.Paper
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
                    id = ArxivId("2403.01234"),
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

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
}
