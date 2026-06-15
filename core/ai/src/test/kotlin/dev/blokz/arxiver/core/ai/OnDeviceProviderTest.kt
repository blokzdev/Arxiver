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
}
