package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.ai.AiProvider
import dev.blokz.arxiver.core.ai.ChatChunk
import dev.blokz.arxiver.core.ai.ChatMessage
import dev.blokz.arxiver.core.ai.ChatRequest
import dev.blokz.arxiver.core.ai.ChatRole
import dev.blokz.arxiver.core.ai.OutputRichness
import dev.blokz.arxiver.core.ai.ProviderCapability
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.core.ai.ToolCall
import dev.blokz.arxiver.core.ai.ToolDef
import dev.blokz.arxiver.core.ai.ToolResult
import dev.blokz.arxiver.data.tool.ToolConsent
import dev.blokz.arxiver.data.tool.ToolContext
import dev.blokz.arxiver.data.tool.ToolExecution
import dev.blokz.arxiver.data.tool.ToolExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Loop-correctness proof for [ChatToolLoop] (P-Tools PT.0) against a scripted single-shot provider
 * and a fake local executor — no network, no corpus. Covers the invariants the repo + red-lines
 * rely on: one terminal Done, never a dangling tool_use, the iteration cap, per-result truncation,
 * supportsTools gating, and no cross-round text duplication.
 */
class ChatToolLoopTest {
    /** A provider that emits a scripted chunk list per call, records requests, and bounds-checks calls. */
    private class ScriptedProvider(
        private val scripts: List<List<ChatChunk>>,
        supportsTools: Boolean = true,
    ) : AiProvider {
        override val id = ProviderId.CLAUDE
        override val capability =
            ProviderCapability(
                contextTokens = 1000,
                streaming = true,
                onDevice = false,
                requiresKey = true,
                richness = OutputRichness.FULL,
                supportsTools = supportsTools,
            )
        val requests = mutableListOf<ChatRequest>()

        override fun chat(request: ChatRequest): Flow<ChatChunk> =
            flow {
                val i = requests.size
                requests += request
                val script =
                    scripts.getOrElse(i) {
                        throw AssertionError("provider called ${i + 1} times; only ${scripts.size} scripted")
                    }
                script.forEach { emit(it) }
            }
    }

    /** A local fake tool executor: echoes, or fails, or returns a long payload — records its calls. */
    private class FakeExecutor(
        private val mode: Mode = Mode.ECHO,
    ) : ToolExecutor {
        enum class Mode { ECHO, FAIL, LONG }

        val calls = mutableListOf<ToolCall>()

        // Honor the two-gate consent like the real registry: offer the tool only when a class is enabled.
        override fun toolDefs(consent: ToolConsent): List<ToolDef> =
            if (consent.library || consent.external) {
                listOf(ToolDef("echo", "echo text", buildJsonObject { put("type", "object") }))
            } else {
                emptyList()
            }

        override suspend fun execute(
            call: ToolCall,
            context: ToolContext,
        ): ToolExecution {
            calls += call
            return when (mode) {
                Mode.FAIL ->
                    ToolExecution(ToolResult(call.id, call.name, "boom", isError = true), call.inputJson, "boom", false)
                Mode.LONG ->
                    ToolExecution(ToolResult(call.id, call.name, "x".repeat(100)), call.inputJson, "long", false)
                Mode.ECHO ->
                    ToolExecution(
                        ToolResult(call.id, call.name, """{"echo":${'"'}${call.inputJson}${'"'}}"""),
                        call.inputJson,
                        call.inputJson,
                        false,
                    )
            }
        }
    }

    private data class LoopResult(
        val emitted: List<ChatChunk>,
        val activity: List<ToolInvocationDraft>,
        val persisted: String?,
        val state: ToolLoopState,
    )

    private fun runLoop(
        loop: ChatToolLoop,
        provider: ScriptedProvider,
        consent: ToolConsent = ToolConsent(library = true, external = false),
        base: ChatRequest = ChatRequest(listOf(ChatMessage(ChatRole.USER, "hi"))),
    ): LoopResult =
        runBlocking {
            val state = ToolLoopState(base)
            val emitted = mutableListOf<ChatChunk>()
            val activity = mutableListOf<ToolInvocationDraft>()
            var persisted: String? = null
            loop.run(
                provider = provider,
                state = state,
                consent = consent,
                toolContext = ToolContext(includeNotes = true, externalEnabled = consent.external),
                emit = { emitted += it },
                onActivity = { activity += it },
                persistTerminal = { persisted = it },
            )
            LoopResult(emitted, activity, persisted, state)
        }

    private fun toolUse(
        id: String,
        args: String = "{}",
    ) = ChatChunk.ToolUse(id, "echo", args)

    @Test
    fun `a single tool call resumes, streams the final answer, and persists one invocation`() {
        val provider =
            ScriptedProvider(
                listOf(
                    listOf(toolUse("t1", """{"text":"hi"}"""), ChatChunk.Done("tool_use")),
                    listOf(ChatChunk.Delta("answer"), ChatChunk.Done("end_turn")),
                ),
            )
        val r = runLoop(ChatToolLoop(FakeExecutor()), provider)

        assertEquals(2, provider.requests.size)
        assertEquals("answer", r.persisted)
        assertEquals("answer", r.emitted.filterIsInstance<ChatChunk.Delta>().joinToString("") { it.text })
        // Mid-loop Done + the ToolUse are suppressed; exactly ONE terminal Done reaches the collector.
        assertEquals(1, r.emitted.filterIsInstance<ChatChunk.Done>().size)
        assertTrue(r.emitted.none { it is ChatChunk.ToolUse })
        assertEquals(1, r.state.invocations.size)
    }

    @Test
    fun `parallel tool calls append exactly one assistant and one TOOL turn`() {
        val provider =
            ScriptedProvider(
                listOf(
                    listOf(toolUse("a"), toolUse("b"), ChatChunk.Done("tool_use")),
                    listOf(ChatChunk.Delta("done"), ChatChunk.Done()),
                ),
            )
        val exec = FakeExecutor()
        val r = runLoop(ChatToolLoop(exec), provider)

        assertEquals(listOf("a", "b"), exec.calls.map { it.id })
        // The resume request carries ONE assistant(tool_use x2) + ONE TOOL(results x2) — never N turns.
        val added = provider.requests[1].messages
        val assistantTurns = added.filter { it.role == ChatRole.ASSISTANT && it.toolCalls.isNotEmpty() }
        val toolTurns = added.filter { it.role == ChatRole.TOOL }
        assertEquals(1, assistantTurns.size)
        assertEquals(2, assistantTurns.single().toolCalls.size)
        assertEquals(1, toolTurns.size)
        assertEquals(2, toolTurns.single().toolResults.size)
        assertEquals(listOf(0, 1), r.state.invocations.map { it.ordinal })
    }

    @Test
    fun `the iteration cap omits tools on the final round and terminates without a dangling tool_use`() {
        val provider =
            ScriptedProvider(
                listOf(
                    listOf(toolUse("t0"), ChatChunk.Done("tool_use")),
                    listOf(toolUse("t1"), ChatChunk.Done("tool_use")),
                    listOf(ChatChunk.Delta("capped"), ChatChunk.Done()),
                ),
            )
        val r = runLoop(ChatToolLoop(FakeExecutor(), maxIterations = 2), provider)

        assertEquals(3, provider.requests.size)
        assertTrue(provider.requests[0].tools.isNotEmpty(), "tools attached on early rounds")
        assertTrue(provider.requests[2].tools.isEmpty(), "cap round omits tools to force a text answer")
        assertEquals("capped", r.persisted)
        // No dangling tool_use: the last message on the final request is a TOOL result, not an open call.
        assertEquals(ChatRole.TOOL, provider.requests[2].messages.last().role)
    }

    @Test
    fun `each tool result is truncated to the per-result char budget`() {
        val provider =
            ScriptedProvider(
                listOf(
                    listOf(toolUse("t0"), ChatChunk.Done("tool_use")),
                    listOf(ChatChunk.Delta("ok"), ChatChunk.Done()),
                ),
            )
        runLoop(ChatToolLoop(FakeExecutor(FakeExecutor.Mode.LONG), perResultCharBudget = 20), provider)

        val refed = provider.requests[1].messages.last { it.role == ChatRole.TOOL }.toolResults.single()
        assertTrue(refed.contentJson.length <= 20, "content=${refed.contentJson}")
        assertTrue(refed.contentJson.endsWith("…[truncated]"))
    }

    @Test
    fun `a tool execution error is fed back, not fatal, and the turn completes`() {
        val provider =
            ScriptedProvider(
                listOf(
                    listOf(toolUse("t0"), ChatChunk.Done("tool_use")),
                    listOf(ChatChunk.Delta("recovered"), ChatChunk.Done()),
                ),
            )
        val r = runLoop(ChatToolLoop(FakeExecutor(FakeExecutor.Mode.FAIL)), provider)

        assertEquals("recovered", r.persisted)
        assertTrue(provider.requests[1].messages.last { it.role == ChatRole.TOOL }.toolResults.single().isError)
    }

    @Test
    fun `ToolConsent NONE offers no tools even to a tool-capable provider (opt-in gate)`() {
        val provider = ScriptedProvider(listOf(listOf(ChatChunk.Delta("plain"), ChatChunk.Done())))
        val exec = FakeExecutor()
        val r = runLoop(ChatToolLoop(exec), provider, consent = ToolConsent.NONE)

        assertTrue(provider.requests[0].tools.isEmpty(), "the user opted out — no tool leaves the device")
        assertTrue(exec.calls.isEmpty())
        assertEquals("plain", r.persisted)
    }

    @Test
    fun `an enabled consent class offers the tools to a tool-capable provider`() {
        val provider = ScriptedProvider(listOf(listOf(ChatChunk.Delta("plain"), ChatChunk.Done())))
        runLoop(ChatToolLoop(FakeExecutor()), provider, consent = ToolConsent(library = true, external = false))

        assertTrue(provider.requests[0].tools.isNotEmpty(), "opted in + tool-capable ⇒ tools attached")
    }

    @Test
    fun `a provider without tool support is offered no tools and runs a single round`() {
        val provider =
            ScriptedProvider(
                listOf(listOf(ChatChunk.Delta("plain"), ChatChunk.Done())),
                supportsTools = false,
            )
        val exec = FakeExecutor()
        val r = runLoop(ChatToolLoop(exec), provider)

        assertEquals(1, provider.requests.size)
        assertTrue(provider.requests[0].tools.isEmpty())
        assertTrue(exec.calls.isEmpty(), "the executor is never invoked when tools aren't supported")
        assertEquals("plain", r.persisted)
    }

    @Test
    fun `multi-round assistant turns carry only that round's text, never the cumulative body`() {
        val provider =
            ScriptedProvider(
                listOf(
                    listOf(ChatChunk.Delta("A"), toolUse("t0"), ChatChunk.Done("tool_use")),
                    listOf(ChatChunk.Delta("B"), toolUse("t1"), ChatChunk.Done("tool_use")),
                    listOf(ChatChunk.Delta("C"), ChatChunk.Done()),
                ),
            )
        val r = runLoop(ChatToolLoop(FakeExecutor()), provider)

        val assistantTurns = r.state.messages.filter { it.role == ChatRole.ASSISTANT && it.toolCalls.isNotEmpty() }
        assertEquals(listOf("A", "B"), assistantTurns.map { it.content }, "no cross-round text duplication")
        assertEquals("ABC", r.persisted, "the persisted body is the cumulative answer")
    }

    @Test
    fun `over-calling the scripted provider surfaces an AssertionError, not an index crash`() {
        // Only one call scripted, but a tool round forces a second — the fake must fail loudly.
        val provider = ScriptedProvider(listOf(listOf(toolUse("t0"), ChatChunk.Done("tool_use"))))
        assertFailsWith<AssertionError> { runLoop(ChatToolLoop(FakeExecutor()), provider) }
    }
}
