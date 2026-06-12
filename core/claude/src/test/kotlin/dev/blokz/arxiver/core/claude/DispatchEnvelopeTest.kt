package dev.blokz.arxiver.core.claude

import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.Paper
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DispatchEnvelopeTest {
    private val builder = PayloadBuilder(appVersion = "1.0.0", now = { Instant.parse("2026-06-12T10:00:00Z") })

    private fun paper(
        id: String,
        title: String,
    ) = PaperWithAnnotations(
        paper =
            Paper(
                id = ArxivId(id),
                latestVersion = 1,
                title = title,
                abstract = "Abstract of $title.",
                publishedAt = Instant.parse("2024-03-02T18:00:01Z"),
                updatedAt = Instant.parse("2024-03-02T18:00:01Z"),
                primaryCategory = "cs.LG",
                categories = listOf("cs.LG"),
                authors = listOf("Ada Researcher"),
            ),
    )

    private fun payloadJson(
        action: RoutineAction,
        instruction: String,
        papers: List<PaperWithAnnotations>,
    ): String =
        (
            builder.build(
                action = action,
                instruction = instruction,
                papers = papers,
                includeNotes = false,
                librarySize = 1,
            ) as PayloadResult.Ready
        ).json

    @Test
    fun `ping envelope stands the routine down and carries no payload`() {
        val text = DispatchEnvelope.render(payloadJson(RoutineAction.PING, "ping", emptyList()))

        assertTrue(text.startsWith("ARXIVER CONNECTIVITY TEST"))
        assertTrue("skip your routine's normal instructions" in text)
        assertTrue("end the run" in text)
        // No JSON fence, no dispatch header — nothing for the routine to act on.
        assertFalse("```" in text)
        assertFalse("ARXIVER RESEARCH DISPATCH" in text)
    }

    @Test
    fun `dispatch envelope is self-describing with instruction, paper list, and fenced json`() {
        val json =
            payloadJson(
                RoutineAction.DIGEST,
                "Focus on the methods sections.",
                listOf(paper("2401.00001", "Quantum Widgets"), paper("2401.00002", "Sparse Gadgets")),
            )
        val text = DispatchEnvelope.render(json)

        assertTrue(text.startsWith("ARXIVER RESEARCH DISPATCH"))
        assertTrue("Action: digest" in text)
        assertTrue("Papers attached: 2" in text)
        assertTrue("MY INSTRUCTION FOR THIS RUN:" in text)
        assertTrue("Focus on the methods sections." in text)
        assertTrue("- Quantum Widgets (arXiv:2401.00001)" in text)
        assertTrue("- Sparse Gadgets (arXiv:2401.00002)" in text)
        // The canonical payload is embedded verbatim inside a json fence.
        assertTrue("```json\n$json\n```" in text)
        assertTrue("pdf_url" in text)
    }

    @Test
    fun `blank custom instruction gets an explicit placeholder`() {
        val text =
            DispatchEnvelope.render(
                payloadJson(RoutineAction.CUSTOM, "", listOf(paper("2401.00001", "T"))),
            )
        assertTrue("(none provided" in text)
    }

    @Test
    fun `long paper lists are truncated in the summary but complete in the json`() {
        val papers = (1..14).map { paper("2401.%05d".format(it), "Paper $it") }
        val json = payloadJson(RoutineAction.DIGEST, "x", papers)
        val text = DispatchEnvelope.render(json)

        assertTrue("…and 4 more" in text)
        assertTrue("Papers attached: 14" in text)
        // Every paper still present in the fenced payload.
        assertEquals(14, Regex("\"arxiv_id\"").findAll(text).count())
    }

    @Test
    fun `unparseable payload falls back to framed json`() {
        val text = DispatchEnvelope.render("{not json at all")
        assertTrue(text.startsWith("ARXIVER RESEARCH DISPATCH"))
        assertTrue("{not json at all" in text)
    }
}
