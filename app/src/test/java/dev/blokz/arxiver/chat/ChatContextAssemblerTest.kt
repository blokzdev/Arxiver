package dev.blokz.arxiver.chat

import dev.blokz.arxiver.core.ai.ChatImage
import dev.blokz.arxiver.core.ai.ChatRole
import dev.blokz.arxiver.core.ai.OutputRichness
import dev.blokz.arxiver.core.ai.ProviderCapability
import dev.blokz.arxiver.core.database.entity.ChunkEmbeddingEntity
import dev.blokz.arxiver.core.search.Provenance
import dev.blokz.arxiver.core.search.RetrievedChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatContextAssemblerTest {
    private val assembler = ChatContextAssembler(maxOutputTokens = 0, safetyMarginTokens = 0)

    /** A cloud (FULL) capability — the default for most assembler tests. */
    private fun cap(
        contextTokens: Int = 100_000,
        vision: Boolean = false,
    ) = ProviderCapability(
        contextTokens = contextTokens,
        streaming = true,
        onDevice = false,
        requiresKey = true,
        richness = OutputRichness.FULL,
        vision = vision,
    )

    /** An on-device capability at a given richness (PA.2): PLAIN (Nano/light) or STRUCTURED (Gemma). */
    private fun onDeviceCap(
        richness: OutputRichness,
        contextTokens: Int = 100_000,
    ) = ProviderCapability(
        contextTokens = contextTokens,
        streaming = true,
        onDevice = true,
        requiresKey = false,
        richness = richness,
    )

    private fun chunk(
        id: Long,
        text: String,
        score: Double,
        sourceKind: String = ChunkEmbeddingEntity.SOURCE_ABSTRACT,
    ) = RetrievedChunk(id, "2401.0000$id", text, score, Provenance.SEMANTIC, sourceKind)

    @Test
    fun `folds chunks and question into the final user turn with a system prompt`() {
        val request =
            assembler.assemble(
                question = "What is the main result?",
                chunks = listOf(chunk(1, "Transformers scale well.", 0.9)),
                history = emptyList(),
                includeNotes = true,
                capability = cap(),
            ).request

        assertTrue(request.system!!.startsWith(ChatContextAssembler.SYSTEM_PROMPT))
        val last = request.messages.last()
        assertEquals(ChatRole.USER, last.role)
        assertTrue(last.content.contains("Transformers scale well."))
        assertTrue(last.content.contains("What is the main result?"))
    }

    @Test
    fun `note chunks are dropped when notes are not included`() {
        val chunks =
            listOf(
                chunk(1, "Abstract sentence.", 0.9),
                chunk(2, "Private note text.", 0.8, ChunkEmbeddingEntity.SOURCE_NOTE),
            )

        val withNotes = assembler.assemble("q", chunks, emptyList(), includeNotes = true, capability = cap()).request
        assertTrue(withNotes.messages.last().content.contains("Private note text."))

        val without = assembler.assemble("q", chunks, emptyList(), includeNotes = false, capability = cap()).request
        assertFalse(without.messages.last().content.contains("Private note text."))
        assertTrue(without.messages.last().content.contains("Abstract sentence."))
    }

    @Test
    fun `prior turns precede the question turn`() {
        val request =
            assembler.assemble(
                question = "follow-up",
                chunks = emptyList(),
                history =
                    listOf(
                        ChatTurn(ChatRole.USER, "first question"),
                        ChatTurn(ChatRole.ASSISTANT, "first answer"),
                    ),
                includeNotes = true,
                capability = cap(),
            ).request

        assertEquals(3, request.messages.size)
        assertEquals("first question", request.messages[0].content)
        assertEquals(ChatRole.ASSISTANT, request.messages[1].role)
        assertTrue(request.messages.last().content.contains("follow-up"))
    }

    @Test
    fun `a tight context window keeps the top-scored chunk and the question`() {
        // Two equal-length (equal-cost) chunks; only the higher-scored one should fit.
        val chunks = listOf(chunk(1, "low-scored-chunk-body!", 0.1), chunk(2, "high-scored-chunk-body", 0.9))
        // Budget after system+question (~20 tokens) leaves room for one ~14-token chunk, not two.
        // cap() is a cloud capability, so the system prompt is base + the math addendum.
        val systemTokens =
            (ChatContextAssembler.SYSTEM_PROMPT.length + ChatContextAssembler.CLOUD_RICH_ADDENDUM.length + 3) / 4
        val request =
            assembler.assemble(
                "q",
                chunks,
                emptyList(),
                includeNotes = true,
                capability = cap(contextTokens = systemTokens + 21),
            ).request

        val content = request.messages.last().content
        assertTrue(content.contains("high-scored-chunk-body"), "keeps the higher-scored chunk")
        assertFalse(content.contains("low-scored-chunk-body"), "drops the lower-scored chunk")
        assertTrue(content.contains("Question: q"))
    }

    @Test
    fun `standard mode adds no directive — byte-identical user turn`() {
        val chunks = listOf(chunk(1, "Body.", 0.9))
        val default = assembler.assemble("q", chunks, emptyList(), includeNotes = true, capability = cap()).request
        val standard =
            assembler.assemble(
                "q",
                chunks,
                emptyList(),
                includeNotes = true,
                capability = cap(),
                mode = ChatMode.STANDARD,
            )
                .request
        assertEquals(default.messages.last().content, standard.messages.last().content)
        assertFalse(standard.messages.last().content.contains(ChatContextAssembler.QUICK_DIRECTIVE))
        assertFalse(standard.messages.last().content.contains(ChatContextAssembler.MAX_DIRECTIVE))
    }

    @Test
    fun `quick mode prepends the quick directive`() {
        val content =
            assembler.assemble(
                "q",
                emptyList(),
                emptyList(),
                includeNotes = true,
                capability = cap(),
                mode = ChatMode.QUICK,
            )
                .request.messages.last().content
        assertTrue(content.contains(ChatContextAssembler.QUICK_DIRECTIVE))
        assertTrue(content.contains("Question: q"))
    }

    @Test
    fun `max mode on a cloud model includes the rich nudge`() {
        val content =
            assembler.assemble(
                "q",
                emptyList(),
                emptyList(),
                includeNotes = true,
                capability = cap(),
                mode = ChatMode.MAX,
            )
                .request.messages.last().content
        assertTrue(content.contains(ChatContextAssembler.MAX_DIRECTIVE))
        assertTrue(content.contains(ChatContextAssembler.MAX_RICH_SUFFIX.trim()))
    }

    @Test
    fun `max mode on an on-device model omits the rich nudge`() {
        val onDevice =
            onDeviceCap(OutputRichness.PLAIN)
        val content =
            assembler.assemble(
                "q",
                emptyList(),
                emptyList(),
                includeNotes = true,
                capability = onDevice,
                mode = ChatMode.MAX,
            )
                .request.messages.last().content
        assertTrue(content.contains(ChatContextAssembler.MAX_DIRECTIVE))
        assertFalse(content.contains(ChatContextAssembler.MAX_RICH_SUFFIX.trim()))
    }

    // --- P-Atlas PA.2: per-engine richness ladder ---

    @Test
    fun `structured (gemma) system teaches the low-syntax TABLE format, not GFM pipes or LaTeX or Mermaid`() {
        val system =
            assembler.assemble(
                "q",
                emptyList(),
                emptyList(),
                includeNotes = true,
                capability = onDeviceCap(OutputRichness.STRUCTURED),
            ).request.system!!
        assertTrue(system.startsWith(ChatContextAssembler.SYSTEM_PROMPT))
        // PA.4: the 1-shot example is the sentinel intermediate the app renders, not GFM pipes.
        assertTrue(system.contains("TABLE::"), "the fence is taught")
        assertTrue(system.contains("Aspect ~|~ A ~|~ B"), "the 1-shot ~|~ example is present")
        assertFalse(system.contains("| Aspect | A | B |"), "no GFM-pipe exemplar (small models break it)")
        assertFalse(system.contains("Use LaTeX"), "no LaTeX invitation on-device")
        assertFalse(system.contains("```mermaid"), "no Mermaid invitation on-device")
    }

    @Test
    fun `structured (gemma) max omits the rich suffix and follow-ups`() {
        val content =
            assembler.assemble(
                "q",
                emptyList(),
                emptyList(),
                includeNotes = true,
                capability = onDeviceCap(OutputRichness.STRUCTURED),
                mode = ChatMode.MAX,
            ).request.messages.last().content
        assertTrue(content.contains(ChatContextAssembler.MAX_DIRECTIVE))
        assertFalse(content.contains(ChatContextAssembler.MAX_RICH_SUFFIX.trim()))
        assertFalse(content.contains("FOLLOWUPS"))
    }

    @Test
    fun `richness changes only the system prompt — the standard user turn is identical across tiers`() {
        val chunks = listOf(chunk(1, "Body.", 0.9))

        fun userTurn(c: ProviderCapability) =
            assembler.assemble("q", chunks, emptyList(), includeNotes = true, capability = c).request
        val full = userTurn(cap())
        val structured = userTurn(onDeviceCap(OutputRichness.STRUCTURED))
        val plain = userTurn(onDeviceCap(OutputRichness.PLAIN))
        // The user turn (chunks + question, no STANDARD directive) is byte-identical across tiers.
        assertEquals(full.messages.last().content, structured.messages.last().content)
        assertEquals(full.messages.last().content, plain.messages.last().content)
        // The system prompts differ by exactly their tier addendum (FULL byte-identical to today).
        assertTrue(full.system!!.endsWith(ChatContextAssembler.CLOUD_RICH_ADDENDUM))
        assertTrue(structured.system!!.endsWith(ChatContextAssembler.STRUCTURED_RICH_ADDENDUM))
        assertEquals(ChatContextAssembler.SYSTEM_PROMPT, plain.system)
    }

    @Test
    fun `the STRUCTURED addendum's RAG-budget cost is bounded by its own size (PA_0a)`() {
        // Six realistic ~70-token chunks (≈62 body + 8 label each); the MARK markers let us count
        // exactly how many survive packing. This is the headless half of PA.0a (table validity / the
        // TABLE:: format adherence itself needs a real Gemma stream — VERIFICATION.md K9).
        val chunks = (1..6).map { chunk(it.toLong(), "MARK$it " + "lorem ".repeat(40), 0.95 - it * 0.01) }

        fun retained(
            richness: OutputRichness,
            contextTokens: Int,
        ): Int {
            val content =
                assembler.assemble(
                    "q",
                    chunks,
                    emptyList(),
                    includeNotes = true,
                    capability = onDeviceCap(richness, contextTokens = contextTokens),
                ).request.messages.last().content
            return (1..6).count { content.contains("MARK$it ") }
        }

        // (a) Real on-device window: 4096 tokens has ample headroom, so the PA.4 STRUCTURED addendum
        //     (which teaches the TABLE:: format) is free — all six chunks fit on both tiers.
        assertEquals(6, retained(OutputRichness.PLAIN, 4096))
        assertEquals(6, retained(OutputRichness.STRUCTURED, 4096))

        // (b) Sensitivity (what makes (a) non-tautological): at a deliberately TIGHT budget the
        //     addendum's cost is *bounded by its own size* — it drops no more chunks than its tokens
        //     displace (≈ addendum_tokens / chunk_tokens), never a catastrophic collapse. Self-adjusts
        //     to the addendum length, so it stays honest as the prompt evolves.
        val addendumTokens = (ChatContextAssembler.STRUCTURED_RICH_ADDENDUM.length + 3) / 4
        val maxDrop = addendumTokens / 70 + 1 // ~70-token chunks; +1 for the packing boundary
        val tight = (ChatContextAssembler.SYSTEM_PROMPT.length + 3) / 4 + 300
        val plainTight = retained(OutputRichness.PLAIN, tight)
        val structuredTight = retained(OutputRichness.STRUCTURED, tight)
        assertTrue(plainTight in 3..6, "the tight budget actually exercises packing: $plainTight")
        assertTrue(
            plainTight - structuredTight <= maxDrop,
            "STRUCTURED drops at most $maxDrop chunk(s) (its own size); plain=$plainTight structured=$structuredTight",
        )
        assertTrue(structuredTight >= 1, "RAG is never fully starved by the addendum")
    }

    // --- R3d.3: vision attachment ---

    @Test
    fun `a vision attachment folds onto the final user turn (vision provider)`() {
        val img = ChatImage("image/jpeg", "QUJD", "page 1 of arXiv:2401.00001")
        val request =
            assembler.assemble(
                "q",
                emptyList(),
                emptyList(),
                includeNotes = true,
                capability = cap(vision = true),
                attachment = img,
            ).request
        assertEquals(listOf(img), request.messages.last().images)
    }

    @Test
    fun `a non-vision provider drops the attachment (defense-in-depth)`() {
        val img = ChatImage("image/jpeg", "QUJD")
        val request =
            assembler.assemble(
                "q",
                emptyList(),
                emptyList(),
                includeNotes = true,
                capability = cap(vision = false),
                attachment = img,
            ).request
        assertTrue(request.messages.last().images.isEmpty())
    }

    @Test
    fun `a null attachment is byte-identical to the no-arg call`() {
        val noArg = assembler.assemble("q", emptyList(), emptyList(), includeNotes = true, capability = cap()).request
        val nullAttach =
            assembler.assemble(
                "q",
                emptyList(),
                emptyList(),
                includeNotes = true,
                capability = cap(),
                attachment = null,
            )
                .request
        assertEquals(noArg.messages.last().content, nullAttach.messages.last().content)
        assertTrue(nullAttach.messages.last().images.isEmpty())
    }

    @Test
    fun `cited chunks are returned in citation order (highest score first)`() {
        // [n] order = the order chunks are labeled in the user turn = highest-scored first.
        val chunks = listOf(chunk(1, "Alpha.", 0.4), chunk(2, "Beta.", 0.9))
        val assembled = assembler.assemble("q", chunks, emptyList(), includeNotes = true, capability = cap())

        assertEquals(listOf("2401.00002", "2401.00001"), assembled.citedChunks.map { it.paperId })
        assertEquals("Beta.", assembled.citedChunks.first().text)
    }

    // --- R3b.2: model follow-ups directive + extraction ---

    @Test
    fun `max cloud asks for model follow-ups but on-device max does not`() {
        val cloud =
            assembler.assemble(
                "q",
                emptyList(),
                emptyList(),
                includeNotes = true,
                capability = cap(),
                mode = ChatMode.MAX,
            )
                .request.messages.last().content
        assertTrue(cloud.contains(ChatContextAssembler.MAX_FOLLOWUPS_SUFFIX.trim()))

        val onDevice =
            onDeviceCap(OutputRichness.PLAIN)
        val dev =
            assembler.assemble(
                "q",
                emptyList(),
                emptyList(),
                includeNotes = true,
                capability = onDevice,
                mode = ChatMode.MAX,
            )
                .request.messages.last().content
        assertFalse(dev.contains("FOLLOWUPS"))
    }

    @Test
    fun `standard and quick never ask for follow-ups`() {
        val std =
            assembler.assemble(
                "q",
                emptyList(),
                emptyList(),
                includeNotes = true,
                capability = cap(),
                mode = ChatMode.STANDARD,
            )
                .request.messages.last().content
        val quick =
            assembler.assemble(
                "q",
                emptyList(),
                emptyList(),
                includeNotes = true,
                capability = cap(),
                mode = ChatMode.QUICK,
            )
                .request.messages.last().content
        assertFalse(std.contains("FOLLOWUPS"))
        assertFalse(quick.contains("FOLLOWUPS"))
    }

    @Test
    fun `extractFollowUps parses and strips a trailing sentinel`() {
        val (body, ups) = extractFollowUps("The answer.\n\nFOLLOWUPS:: What are the limits? | How does it compare?")
        assertEquals("The answer.", body)
        assertEquals(listOf("What are the limits?", "How does it compare?"), ups)
    }

    @Test
    fun `extractFollowUps leaves a sentinel-free answer unchanged`() {
        val (body, ups) = extractFollowUps("Just a normal answer.")
        assertEquals("Just a normal answer.", body)
        assertTrue(ups.isEmpty())
    }

    @Test
    fun `extractFollowUps only considers the final non-blank line`() {
        val ans = "Intro that mentions FOLLOWUPS:: not a block.\n\nReal conclusion here."
        val (body, ups) = extractFollowUps(ans)
        assertTrue(ups.isEmpty(), "a mid-answer FOLLOWUPS:: must not be parsed or stripped")
        assertEquals(ans, body)
    }

    @Test
    fun `extractFollowUps caps at three, dedups, drops blanks, and accepts a bullet marker`() {
        assertEquals(listOf("a", "b", "c"), extractFollowUps("X\nFOLLOWUPS:: a | a | b |  | c | d").second)
        assertEquals(listOf("q1", "q2"), extractFollowUps("X\n- FOLLOWUPS:: q1 | q2").second)
    }

    @Test
    fun `toolsAvailable appends the tool addendum and the default omits it (byte-identity, P-Tools PT1)`() {
        val withTool =
            assembler.assemble(
                "q",
                emptyList(),
                emptyList(),
                includeNotes = true,
                capability = cap(),
                toolsAvailable = true,
            ).request
        val without =
            assembler.assemble("q", emptyList(), emptyList(), includeNotes = true, capability = cap()).request

        assertTrue(withTool.system!!.contains("search_my_library"))
        assertTrue(withTool.system!!.endsWith(ChatContextAssembler.TOOLS_PRESENT_ADDENDUM))
        // Default (toolsAvailable=false) is byte-identical to the pre-P-Tools cloud system prompt.
        assertFalse(without.system!!.contains("search_my_library"))
        assertTrue(without.system!!.endsWith(ChatContextAssembler.CLOUD_RICH_ADDENDUM))
    }
}
