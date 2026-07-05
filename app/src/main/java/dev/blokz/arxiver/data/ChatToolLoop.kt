package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.ai.AiException
import dev.blokz.arxiver.core.ai.AiProvider
import dev.blokz.arxiver.core.ai.ChatChunk
import dev.blokz.arxiver.core.ai.ChatMessage
import dev.blokz.arxiver.core.ai.ChatRequest
import dev.blokz.arxiver.core.ai.ChatRole
import dev.blokz.arxiver.core.ai.ToolCall
import dev.blokz.arxiver.core.ai.ToolDef
import dev.blokz.arxiver.core.ai.ToolResult
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.database.entity.ToolInvocationEntity
import dev.blokz.arxiver.data.tool.NoToolExecutor
import dev.blokz.arxiver.data.tool.ToolExecutor

/** An executed tool step accumulated by the loop before the assistant message id + clock are bound. */
data class ToolInvocationDraft(
    val toolName: String,
    val query: String,
    val resultSummary: String,
    val egress: Boolean,
    val ordinal: Int,
) {
    fun toEntity(
        messageId: Long,
        createdAt: Long,
    ): ToolInvocationEntity =
        ToolInvocationEntity(
            messageId = messageId,
            toolName = toolName,
            query = query,
            resultSummary = resultSummary,
            egress = egress,
            ordinal = ordinal,
            createdAt = createdAt,
        )
}

/**
 * Mutable per-turn state the loop grows across tool rounds. The repository reads [assistantText]
 * (the body to persist) + [invocations] (the tool rows) in its finalize arms, which is why an
 * interrupted agentic turn degrades to today's single-turn finalize with zero dangling tool rows.
 */
class ToolLoopState(private val base: ChatRequest) {
    /** The running message list sent to the provider — grows by one assistant + one TOOL turn per round. */
    val messages: MutableList<ChatMessage> = base.messages.toMutableList()

    /** The full assistant answer concatenated across ALL rounds — the body the repo persists. */
    val assistantText = StringBuilder()

    /** Executed tool steps in call order — written to `tool_invocations` ONLY at the terminal Done. */
    val invocations = mutableListOf<ToolInvocationDraft>()

    fun request(tools: List<ToolDef>): ChatRequest = base.copy(messages = messages.toList(), tools = tools)
}

/**
 * The agentic tool loop (P-Tools PT.0, SPEC-P-TOOLS §5). Sits ABOVE the single-shot provider: each
 * `provider.chat()` is one HTTP round-trip; this drives the multi-turn loop, executes tools LOCALLY
 * via [executor], and re-calls until the model produces a final text answer or the iteration cap.
 *
 * Invariants the repo + red-lines rely on:
 * - **One terminal Done.** Intermediate provider Dones are suppressed; exactly one [ChatChunk.Done]
 *   reaches the collector — the ViewModel finalizes its bubble on the first Done it sees.
 * - **Never a dangling tool_use.** Buffered calls become ONE assistant turn + ONE TOOL turn (never
 *   N — Anthropic 400s otherwise); on the cap round tools are omitted so the model must answer as
 *   text. Tool steps live only in in-memory [ToolLoopState] until the terminal write, so an
 *   interruption persists zero tool rows.
 * - **Bounded.** At most [maxIterations] tool rounds; each tool result truncated to [perResultCharBudget].
 */
class ChatToolLoop(
    private val executor: ToolExecutor = NoToolExecutor,
    private val maxIterations: Int = MAX_TOOL_ITERATIONS,
    private val perResultCharBudget: Int = DEFAULT_RESULT_CHAR_BUDGET,
) {
    suspend fun run(
        provider: AiProvider,
        state: ToolLoopState,
        emit: suspend (ChatChunk) -> Unit,
        onActivity: suspend (ToolInvocationDraft) -> Unit,
        persistTerminal: suspend (String) -> Unit,
    ) {
        // Gate STRICTLY on supportsTools: never hand tools to a provider that would ignore them
        // (on-device) and leave the loop waiting on a tool_use that never comes.
        val toolDefs = if (provider.capability.supportsTools) executor.toolDefs() else emptyList()
        var iteration = 0
        while (true) {
            val lastRound = iteration >= maxIterations
            val request = state.request(tools = if (lastRound) emptyList() else toolDefs)
            val buffered = mutableListOf<ToolCall>()
            val roundText = StringBuilder() // THIS round's text only — the assistant turn's content
            var sawToolUseStop = false
            provider.chat(request).collect { chunk ->
                when (chunk) {
                    is ChatChunk.Delta -> {
                        roundText.append(chunk.text)
                        state.assistantText.append(chunk.text)
                        emit(chunk) // stream live; the full answer accumulates across rounds
                    }
                    is ChatChunk.ToolUse -> buffered += ToolCall(chunk.id, chunk.name, chunk.inputJson)
                    // Suppress the provider's Done — the loop synthesizes the ONE terminal Done below.
                    is ChatChunk.Done -> sawToolUseStop = chunk.stopReason == "tool_use"
                }
            }
            // A tool_use stop reason with no parsed call = corrupted stream → fail loudly, don't hang.
            if (buffered.isEmpty() && sawToolUseStop) {
                throw AiException(AppError.Upstream(0, "tool_use stop reason with no tool call"))
            }
            if (buffered.isEmpty() || lastRound) {
                // Terminal text turn (or cap reached — a stray tool_use is discarded, never dangling).
                persistTerminal(state.assistantText.toString())
                emit(ChatChunk.Done())
                return
            }
            // The model asked for tools: execute ALL (serial), append ONE assistant + ONE TOOL turn.
            val results = mutableListOf<ToolResult>()
            buffered.forEach { call ->
                val exec = executor.execute(call)
                val draft =
                    ToolInvocationDraft(
                        toolName = call.name,
                        query = exec.query,
                        resultSummary = exec.resultSummary,
                        egress = exec.egress,
                        ordinal = state.invocations.size,
                    )
                state.invocations += draft
                onActivity(draft)
                val clipped = truncateToolResult(exec.result.contentJson, perResultCharBudget)
                results += exec.result.copy(contentJson = clipped)
            }
            state.messages +=
                ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = roundText.toString(),
                    toolCalls = buffered.toList(),
                )
            state.messages +=
                ChatMessage(
                    role = ChatRole.TOOL,
                    content = results.joinToString("\n") { it.contentJson },
                    toolResults = results.toList(),
                )
            iteration++
        }
    }

    companion object {
        const val MAX_TOOL_ITERATIONS = 5
        const val DEFAULT_RESULT_CHAR_BUDGET = 4000
    }
}

/** Bound a tool result to [budget] chars, surrogate-safe, with a truncation marker. */
internal fun truncateToolResult(
    content: String,
    budget: Int,
): String {
    if (content.length <= budget) return content
    val marker = "…[truncated]"
    // A budget too small to hold even the marker degrades to a hard clip (defensive; unreachable
    // in practice — DEFAULT_RESULT_CHAR_BUDGET is 4000).
    if (budget <= marker.length) return content.take(budget)
    var end = budget - marker.length
    if (end > 0 && content[end - 1].isHighSurrogate()) end -= 1
    return content.substring(0, end) + marker
}
