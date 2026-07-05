package dev.blokz.arxiver.data.tool

import dev.blokz.arxiver.core.ai.ToolCall
import dev.blokz.arxiver.core.ai.ToolDef
import dev.blokz.arxiver.core.ai.ToolResult

/**
 * Executes model-requested tool calls LOCALLY (P-Tools). Implementations own the tool catalog and
 * route each call through existing infra — the arXiv limiter + `AllowedHosts` for external tools
 * (PT.2+), or purely on-device for local ones (`search_my_library`, PT.1). Lives in `:app` because
 * executors call `:app`/`:core` repositories; `:core:ai` stays execution-agnostic.
 *
 * [execute] MUST NOT throw — a tool failure returns [ToolResult] with `isError = true` so the model
 * can recover on the next turn rather than aborting the whole conversation.
 */
interface ToolExecutor {
    /** The tools offered to the model this turn (already consent/egress-filtered by the caller). */
    fun toolDefs(): List<ToolDef>

    /** Run one call with the per-turn [context]. Never throws; errors become an error [ToolResult]. */
    suspend fun execute(
        call: ToolCall,
        context: ToolContext,
    ): ToolExecution
}

/**
 * Per-turn context threaded to every tool call (P-Tools PT.1). [includeNotes] mirrors the user's
 * privacy gate for the turn — a local library search must skip note-derived ranking when it's off,
 * so private note text never influences what returns to the cloud model.
 */
data class ToolContext(val includeNotes: Boolean)

/**
 * One executed tool step: the [result] fed back to the model, plus the audit facts the tool loop
 * persists to `tool_invocations` and surfaces in the inline activity log — [query] (human-readable),
 * [resultSummary] (short), and [egress] (did the call leave the device; false for local tools).
 */
data class ToolExecution(
    val result: ToolResult,
    val query: String,
    val resultSummary: String,
    val egress: Boolean,
)

/**
 * The default when no executor is wired (existing chat paths, tests): offers no tools and errors any
 * call — so a provider is never handed tools and the loop terminates after one text turn (identical
 * to pre-P-Tools behavior).
 */
object NoToolExecutor : ToolExecutor {
    override fun toolDefs(): List<ToolDef> = emptyList()

    override suspend fun execute(
        call: ToolCall,
        context: ToolContext,
    ): ToolExecution =
        ToolExecution(
            result = ToolResult(call.id, call.name, "{}", isError = true),
            query = "",
            resultSummary = "",
            egress = false,
        )
}
