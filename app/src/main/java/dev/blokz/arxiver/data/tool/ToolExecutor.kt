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
    /** The tools offered to the model this turn, filtered to the classes the user consented to ([consent]). */
    fun toolDefs(consent: ToolConsent): List<ToolDef>

    /** Run one call with the per-turn [context]. Never throws; errors become an error [ToolResult]. */
    suspend fun execute(
        call: ToolCall,
        context: ToolContext,
    ): ToolExecution
}

/**
 * The per-conversation tool consent for a turn (P-Tools). [library] gates the LOCAL library-search
 * tool (PT.1); [external] gates the EXTERNAL web tools (arXiv, PT.2) — separate because a user may
 * want library-yes / web-no. The registry offers exactly the enabled subset.
 */
data class ToolConsent(
    val library: Boolean,
    val external: Boolean,
) {
    companion object {
        val NONE = ToolConsent(library = false, external = false)
    }
}

/**
 * Per-turn context threaded to every tool call (P-Tools). [includeNotes] mirrors the user's note
 * privacy gate (a local library search skips note-derived ranking when off). [externalEnabled] is the
 * execute-time guard for the first external egress (PT.2): a hallucinated external call in a
 * web-search-off turn is refused before the seam runs, so nothing egresses without consent.
 */
data class ToolContext(
    val includeNotes: Boolean,
    val externalEnabled: Boolean,
)

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
    override fun toolDefs(consent: ToolConsent): List<ToolDef> = emptyList()

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
