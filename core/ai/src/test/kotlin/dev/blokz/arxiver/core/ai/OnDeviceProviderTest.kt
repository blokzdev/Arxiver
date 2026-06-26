package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Delegation/ordering for the on-device provider (SPEC-AI-PROVIDERS §3). */
class OnDeviceProviderTest {
    private val dispatchers =
        object : DispatcherProvider {
            override val io = Dispatchers.Unconfined
            override val default = Dispatchers.Unconfined
            override val main = Dispatchers.Unconfined
        }

    private class FakeEngine(
        override val tier: InferenceTier,
        private val ready: Boolean,
        private val reply: String,
        override val richness: OutputRichness = OutputRichness.PLAIN,
    ) : OnDeviceEngine {
        var generated = false

        override suspend fun isReady(): Boolean = ready

        override fun generate(request: ChatRequest): Flow<ChatChunk> =
            flow {
                generated = true
                emit(ChatChunk.Delta(reply))
                emit(ChatChunk.Done())
            }
    }

    private fun request() = ChatRequest(messages = listOf(ChatMessage(ChatRole.USER, "hi")))

    @Test
    fun `delegates to the ready engine and streams its reply`() =
        runBlocking {
            val gemma = FakeEngine(InferenceTier.GEMMA, ready = true, reply = "from-gemma")
            val provider = OnDeviceProvider(listOf(gemma), dispatchers)

            val chunks = provider.chat(request()).toList()

            assertTrue(gemma.generated)
            assertEquals("from-gemma", chunks.filterIsInstance<ChatChunk.Delta>().joinToString("") { it.text })
            assertTrue(chunks.last() is ChatChunk.Done)
        }

    @Test
    fun `no ready engine surfaces an error`() =
        runBlocking {
            val gemma = FakeEngine(InferenceTier.GEMMA, ready = false, reply = "x")
            val provider = OnDeviceProvider(listOf(gemma), dispatchers)

            val error = assertFailsWith<AiException> { provider.chat(request()).toList() }.error
            assertTrue(error is AppError.Unexpected)
            assertTrue(!gemma.generated)
        }

    @Test
    fun `first ready engine wins (nano before gemma)`() =
        runBlocking {
            val nano = FakeEngine(InferenceTier.NANO, ready = true, reply = "nano")
            val gemma = FakeEngine(InferenceTier.GEMMA, ready = true, reply = "gemma")
            val provider = OnDeviceProvider(listOf(nano, gemma), dispatchers)

            val text =
                provider.chat(
                    request(),
                ).toList().filterIsInstance<ChatChunk.Delta>().joinToString("") { it.text }

            assertEquals("nano", text)
            assertTrue(nano.generated)
            assertTrue(!gemma.generated)
        }

    @Test
    fun `skips an unready engine for a ready one`() =
        runBlocking {
            val nano = FakeEngine(InferenceTier.NANO, ready = false, reply = "nano")
            val gemma = FakeEngine(InferenceTier.GEMMA, ready = true, reply = "gemma")
            val provider = OnDeviceProvider(listOf(nano, gemma), dispatchers)

            val text =
                provider.chat(
                    request(),
                ).toList().filterIsInstance<ChatChunk.Delta>().joinToString("") { it.text }

            assertEquals("gemma", text)
            assertTrue(!nano.generated)
            assertTrue(gemma.generated)
        }

    @Test
    fun `preferred tier overrides default order when ready`() =
        runBlocking {
            val gemma = FakeEngine(InferenceTier.GEMMA, ready = true, reply = "gemma")
            val nano = FakeEngine(InferenceTier.NANO, ready = true, reply = "nano")
            // Default order is Gemma-first, but the user prefers Nano.
            val provider = OnDeviceProvider(listOf(gemma, nano), dispatchers, preferredTier = { InferenceTier.NANO })

            val text =
                provider.chat(
                    request(),
                ).toList().filterIsInstance<ChatChunk.Delta>().joinToString("") { it.text }

            assertEquals("nano", text)
            assertTrue(nano.generated)
            assertTrue(!gemma.generated)
        }

    @Test
    fun `preferred but unready tier falls back to default order`() =
        runBlocking {
            val gemma = FakeEngine(InferenceTier.GEMMA, ready = true, reply = "gemma")
            val nano = FakeEngine(InferenceTier.NANO, ready = false, reply = "nano")
            val provider = OnDeviceProvider(listOf(gemma, nano), dispatchers, preferredTier = { InferenceTier.NANO })

            val text =
                provider.chat(
                    request(),
                ).toList().filterIsInstance<ChatChunk.Delta>().joinToString("") { it.text }

            assertEquals("gemma", text)
            assertTrue(gemma.generated)
            assertTrue(!nano.generated)
        }

    // --- P-Atlas PA.3: 3-engine routing with the LIGHT tier in the middle ---

    private fun streamed(provider: OnDeviceProvider) =
        runBlocking {
            provider.chat(request()).toList().filterIsInstance<ChatChunk.Delta>().joinToString("") { it.text }
        }

    @Test
    fun `gemma beats the light tier when both are ready`() {
        val gemma = FakeEngine(InferenceTier.GEMMA, ready = true, reply = "gemma")
        val light = FakeEngine(InferenceTier.LIGHT, ready = true, reply = "light")
        val nano = FakeEngine(InferenceTier.NANO, ready = true, reply = "nano")
        assertEquals("gemma", streamed(OnDeviceProvider(listOf(gemma, light, nano), dispatchers)))
        assertTrue(!light.generated && !nano.generated)
    }

    @Test
    fun `light tier wins over nano when gemma is unready (the tier's whole purpose)`() {
        val gemma = FakeEngine(InferenceTier.GEMMA, ready = false, reply = "gemma")
        val light = FakeEngine(InferenceTier.LIGHT, ready = true, reply = "light")
        val nano = FakeEngine(InferenceTier.NANO, ready = true, reply = "nano")
        assertEquals("light", streamed(OnDeviceProvider(listOf(gemma, light, nano), dispatchers)))
        assertTrue(light.generated && !nano.generated)
    }

    @Test
    fun `preferred LIGHT tier is picked over a ready gemma`() {
        val gemma = FakeEngine(InferenceTier.GEMMA, ready = true, reply = "gemma")
        val light = FakeEngine(InferenceTier.LIGHT, ready = true, reply = "light")
        val provider = OnDeviceProvider(listOf(gemma, light), dispatchers, preferredTier = { InferenceTier.LIGHT })
        assertEquals("light", streamed(provider))
        assertTrue(!gemma.generated)
    }

    // --- P-Atlas PA.2: richness resolution matches the engine that would stream ---

    @Test
    fun `the static capability richness is a PLAIN placeholder, independent of the engines`() {
        // Even with a STRUCTURED engine wired, the static capability stays PLAIN — the real per-turn
        // value comes from resolveRichness(); a reader that skips that override gets the benign tier.
        val provider =
            OnDeviceProvider(
                listOf(
                    FakeEngine(InferenceTier.GEMMA, ready = true, reply = "x", richness = OutputRichness.STRUCTURED),
                ),
                dispatchers,
            )
        assertEquals(OutputRichness.PLAIN, provider.capability.richness)
    }

    @Test
    fun `resolveRichness returns the picked engine's richness, PLAIN when none ready`() =
        runBlocking {
            val gemma = FakeEngine(InferenceTier.GEMMA, ready = true, reply = "x", richness = OutputRichness.STRUCTURED)
            val nano = FakeEngine(InferenceTier.NANO, ready = true, reply = "x", richness = OutputRichness.PLAIN)
            // First-ready (DI order = Gemma-first) → STRUCTURED, matching who chat() would pick.
            assertEquals(
                OutputRichness.STRUCTURED,
                OnDeviceProvider(listOf(gemma, nano), dispatchers).resolveRichness(),
            )
            // Prefer Nano → PLAIN, again matching the streaming engine.
            assertEquals(
                OutputRichness.PLAIN,
                OnDeviceProvider(listOf(gemma, nano), dispatchers, preferredTier = { InferenceTier.NANO })
                    .resolveRichness(),
            )
            // No engine ready → PLAIN fallback (the assembler then stays on the base prompt).
            val unready =
                FakeEngine(InferenceTier.GEMMA, ready = false, reply = "x", richness = OutputRichness.STRUCTURED)
            assertEquals(OutputRichness.PLAIN, OnDeviceProvider(listOf(unready), dispatchers).resolveRichness())
        }
}
