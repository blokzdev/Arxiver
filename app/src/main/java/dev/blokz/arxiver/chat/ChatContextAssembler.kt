package dev.blokz.arxiver.chat

import dev.blokz.arxiver.core.ai.ChatImage
import dev.blokz.arxiver.core.ai.ChatMessage
import dev.blokz.arxiver.core.ai.ChatRequest
import dev.blokz.arxiver.core.ai.ChatRole
import dev.blokz.arxiver.core.ai.OutputRichness
import dev.blokz.arxiver.core.ai.ProviderCapability
import dev.blokz.arxiver.core.database.entity.ChunkEmbeddingEntity
import dev.blokz.arxiver.core.search.RetrievedChunk

/** A prior chat turn, chronological, fed back as conversational context. */
data class ChatTurn(val role: ChatRole, val content: String)

/**
 * Answer-depth dial (P-Rich R3b): scales an answer's length/richness via a short directive
 * prepended to the user turn — never a system-prompt change. [STANDARD] adds nothing (today's
 * behavior, byte-identical request), so the redaction goldens are untouched.
 */
enum class ChatMode { QUICK, STANDARD, MAX }

private val FOLLOWUPS_LINE = Regex("""^[ \t>*\-]*FOLLOWUPS::[ \t]*(.+)$""")

/**
 * Model-generated follow-ups (P-Rich R3b.2): if [answer]'s **last non-blank line** is a
 * `FOLLOWUPS:: q1 | q2 | q3` sentinel (Max on cloud emits it — see
 * [ChatContextAssembler.MAX_FOLLOWUPS_SUFFIX]), returns the body with that one line removed plus the
 * parsed suggestions (≤3, de-duped). Only the final non-blank line is considered, so a mid-answer
 * or fenced `FOLLOWUPS::` literal is never stripped (and the conclusion is never truncated).
 * Returns `(answer, emptyList())` when absent or malformed. Pure — shared by the ViewModel (display)
 * and the repository (persist) so the rendered and stored bodies always agree.
 */
fun extractFollowUps(answer: String): Pair<String, List<String>> {
    val lines = answer.trimEnd().lines()
    val lastIdx = lines.indexOfLast { it.isNotBlank() }
    if (lastIdx < 0) return answer to emptyList()
    val match = FOLLOWUPS_LINE.matchEntire(lines[lastIdx].trim()) ?: return answer to emptyList()
    val items =
        match.groupValues[1].split('|').map { it.trim() }.filter { it.isNotBlank() }.distinct().take(3)
    if (items.isEmpty()) return answer to emptyList()
    return lines.subList(0, lastIdx).joinToString("\n").trimEnd() to items
}

/**
 * The assembled request plus the chunks actually cited in it, in `[1..n]` order (the
 * subset that fit the budget) — so the UI can map an answer's `[n]` to its source.
 */
data class AssembledChat(val request: ChatRequest, val citedChunks: List<RetrievedChunk>)

/**
 * Builds a provider-neutral [ChatRequest] for a grounded answer (SPEC-AI-PROVIDERS
 * chat orchestration). Folds retrieved chunks into the final user turn as a labeled,
 * citable context block, prepends prior turns, and fits everything inside the
 * provider's context window using a tokenizer-free char/4 proxy (no chat tokenizer
 * exists; providers do their own exact counting). When the budget is tight it drops
 * the oldest history first, then the lowest-scored chunks — the question is never
 * dropped. Note-derived chunks are gated by [includeNotes], mirroring how the
 * dispatch bridge gates notes. Pure / deterministic.
 */
class ChatContextAssembler(
    private val maxOutputTokens: Int = ChatRequest.DEFAULT_MAX_TOKENS,
    private val safetyMarginTokens: Int = SAFETY_MARGIN_TOKENS,
) {
    fun assemble(
        question: String,
        chunks: List<RetrievedChunk>,
        history: List<ChatTurn>,
        includeNotes: Boolean,
        capability: ProviderCapability,
        mode: ChatMode = ChatMode.STANDARD,
        attachment: ChatImage? = null,
        toolsAvailable: Boolean = false,
    ): AssembledChat {
        val gated = if (includeNotes) chunks else chunks.filter { it.sourceKind != ChunkEmbeddingEntity.SOURCE_NOTE }

        // Per-engine richness ladder (P-Atlas PA.2): PLAIN (tiny models) = base prompt (which already
        // invites tables); STRUCTURED (Gemma E2B) = + a table-focused nudge, no LaTeX/Mermaid (too
        // unreliable at ~2B); FULL (cloud) = + the LaTeX-math + Mermaid invitation.
        val system =
            when (capability.richness) {
                OutputRichness.PLAIN -> SYSTEM_PROMPT
                OutputRichness.STRUCTURED -> SYSTEM_PROMPT + STRUCTURED_RICH_ADDENDUM
                OutputRichness.FULL -> SYSTEM_PROMPT + CLOUD_RICH_ADDENDUM
                // P-Tools PT.1: relax "ONLY the provided excerpts" when a tool can fetch more; default
                // false keeps every non-tool turn's system prompt byte-identical.
            } + if (toolsAvailable) TOOLS_PRESENT_ADDENDUM else ""

        // The depth directive rides the user turn (never the system prompt); STANDARD is empty,
        // so a default-mode request is byte-identical to before. Max's rich nudge is cloud-only (FULL).
        val directive = modeDirective(mode, capability.richness)

        var budget =
            capability.contextTokens - maxOutputTokens - safetyMarginTokens -
                estTokens(system) - estTokens(question) - estTokens(directive)

        // Highest-scored chunks first, keep those that fit.
        val keptChunks = mutableListOf<RetrievedChunk>()
        for (chunk in gated.sortedByDescending { it.score }) {
            val cost = estTokens(chunk.text) + CHUNK_LABEL_TOKENS
            if (budget - cost < 0) continue
            budget -= cost
            keptChunks += chunk
        }

        // Most-recent history that still fits, then restore chronological order.
        val keptHistory = ArrayDeque<ChatTurn>()
        for (turn in history.asReversed()) {
            val cost = estTokens(turn.content)
            if (budget - cost < 0) break
            budget -= cost
            keptHistory.addFirst(turn)
        }

        // Attach a vision image (P-Rich R3d) to the final user turn — but ONLY when the resolved
        // provider actually supports vision. This is defense-in-depth: even if a caller passes an
        // image to an on-device/non-vision provider, it is dropped here so it never reaches the wire.
        val images = if (attachment != null && capability.vision) listOf(attachment) else emptyList()

        val messages =
            keptHistory.map { ChatMessage(it.role, it.content) } +
                ChatMessage(ChatRole.USER, userTurn(question, keptChunks, directive), images = images)

        return AssembledChat(
            request = ChatRequest(messages = messages, system = system, maxTokens = maxOutputTokens),
            citedChunks = keptChunks,
        )
    }

    private fun userTurn(
        question: String,
        chunks: List<RetrievedChunk>,
        directive: String,
    ): String =
        buildString {
            // Depth directive first (empty for STANDARD → identical to the pre-R3b turn).
            if (directive.isNotBlank()) {
                append(directive)
                append("\n\n")
            }
            if (chunks.isNotEmpty()) {
                appendLine("Context from the user's library:")
                chunks.forEachIndexed { i, c ->
                    appendLine("[${i + 1}] (arXiv:${c.paperId}) ${c.text}")
                }
                appendLine()
            }
            append("Question: ")
            append(question)
        }

    /** The depth directive for [mode]; Max's rich-output nudge + follow-ups are FULL-only (cloud). */
    private fun modeDirective(
        mode: ChatMode,
        richness: OutputRichness,
    ): String =
        when (mode) {
            ChatMode.QUICK -> QUICK_DIRECTIVE
            ChatMode.STANDARD -> ""
            ChatMode.MAX ->
                if (richness == OutputRichness.FULL) {
                    MAX_DIRECTIVE + MAX_RICH_SUFFIX + MAX_FOLLOWUPS_SUFFIX
                } else {
                    MAX_DIRECTIVE
                }
        }

    private fun estTokens(text: String): Int = (text.length + 3) / 4

    companion object {
        /** Reserve so the char/4 estimate's slack never overruns the real window. */
        const val SAFETY_MARGIN_TOKENS = 256
        private const val CHUNK_LABEL_TOKENS = 8

        /** Depth-dial directives (P-Rich R3b), prepended to the user turn. STANDARD adds nothing. */
        const val QUICK_DIRECTIVE = "Keep the answer to 2–3 sentences — just the essentials."
        const val MAX_DIRECTIVE =
            "Give a thorough, detailed answer: synthesize across the excerpts and surface " +
                "nuances and edge cases."

        /** Appended to [MAX_DIRECTIVE] for cloud models only (small on-device models emit broken markup). */
        const val MAX_RICH_SUFFIX = " Use a Markdown table or a Mermaid diagram when it genuinely clarifies."

        /**
         * Appended for cloud Max only (P-Rich R3b.2): asks for model-generated follow-ups as a final
         * `FOLLOWUPS::` line, parsed + stripped by [extractFollowUps]. Rides the user turn (never the
         * system prompt); on-device Max omits it and falls back to local heuristics.
         */
        const val MAX_FOLLOWUPS_SUFFIX =
            " Finally, on the very last line and with nothing after it, suggest 2–3 natural follow-up " +
                "questions in the form: FOLLOWUPS:: first question | second question | third question " +
                "(separate them with ' | ' and put no other text on that line)."

        /** Model-facing system instruction (English, like the routine starter text). */
        const val SYSTEM_PROMPT =
            "You are a research assistant inside the Arxiver app. Answer the user's question " +
                "using ONLY the provided context excerpts from their library. Cite the excerpts you " +
                "use by their [number]. If the context does not contain the answer, say so plainly " +
                "rather than guessing. Be concise and precise. " +
                "Format your answer in Markdown — use headings, bullet or numbered lists, **bold** " +
                "for key terms, fenced code for code, and a Markdown table when comparing things — " +
                "whenever it makes the answer clearer."

        /**
         * Appended for STRUCTURED (Gemma E2B, P-Atlas PA.4 — "app draws the structure"). A ~2B model
         * emits valid GFM table *syntax* only ~40–85% of the time, so we ask for a **low-syntax
         * intermediate** it nails 0-shot (arXiv:2506.19512) — a `TABLE::` … `::TABLE` block, one row
         * per line, cells split by `~|~`, citations kept inline — and `StructuredTableTransform`
         * deterministically builds a guaranteed-valid table (or a grounded list) from it. Explicitly
         * steers OFF GFM pipes / LaTeX / diagrams (all high-failure at this size; cloud-only). The base
         * `SYSTEM_PROMPT` is left untouched (changing it would ripple into PLAIN/FULL + the goldens).
         */
        const val STRUCTURED_RICH_ADDENDUM =
            " When you compare or list several things across the same aspects, put the comparison in a " +
                "block that starts with a line TABLE:: and ends with a line ::TABLE. Inside, write one " +
                "row per line and separate the cells with ~|~. The first row is the column headers. " +
                "Keep the [number] citations inside the cells they support. For example:\n" +
                "TABLE::\nAspect ~|~ A ~|~ B\nSpeed ~|~ fast [1] ~|~ slower [2]\n::TABLE\n" +
                "Use this only for genuine comparisons; otherwise answer normally. Do not use Markdown " +
                "table pipes, LaTeX math, or diagrams."

        /**
         * Appended for cloud models (small on-device models emit structured output
         * unreliably): invite LaTeX math (KaTeX, P-Rich R1) and Mermaid diagrams (P-Rich R2),
         * which the app renders locally.
         */
        const val CLOUD_RICH_ADDENDUM =
            " Use LaTeX for mathematics — inline as ${'$'}…${'$'} and display equations as " +
                "${'$'}${'$'}…${'$'}${'$'}. When a picture helps, draw it with a Mermaid block " +
                "(```mermaid) — a flowchart, sequence, mindmap, or timeline, or a pie/xychart-beta " +
                "chart. Use these only when they make the answer clearer."

        /**
         * Appended when a tool is available (P-Tools PT.1). The base prompt says "using ONLY the
         * provided context excerpts" — too narrow once the model can fetch more via a tool. Tool
         * results are valid grounding; steer their citations to prose `arXiv:<id>` so they don't
         * collide with the `[number]` excerpt citations.
         */
        const val TOOLS_PRESENT_ADDENDUM =
            " You may also call the search_my_library tool to find more of the user's saved papers; " +
                "treat any papers it returns as valid grounding and cite them in prose as arXiv:<id>."
    }
}
