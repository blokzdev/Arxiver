package dev.blokz.arxiver.core.ai

import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Configured-provider filtering keys off the real [AiKeyVault], so this runs
 * under Robolectric (SPEC-AI-PROVIDERS §2).
 */
@RunWith(RobolectricTestRunner::class)
class ProviderRegistryTest {
    private val vault = AiKeyVault(ApplicationProvider.getApplicationContext())

    private val dispatchers =
        object : DispatcherProvider {
            override val io = Dispatchers.Unconfined
            override val default = Dispatchers.Unconfined
            override val main = Dispatchers.Unconfined
        }

    private class FakeProvider(
        override val id: ProviderId,
        override val capability: ProviderCapability,
    ) : AiProvider {
        override fun chat(request: ChatRequest): Flow<ChatChunk> = flowOf(ChatChunk.Done())
    }

    private val claude =
        FakeProvider(
            ProviderId.CLAUDE,
            ProviderCapability(200_000, streaming = true, onDevice = false, requiresKey = true),
        )
    private val gemini =
        FakeProvider(
            ProviderId.GEMINI,
            ProviderCapability(1_000_000, streaming = true, onDevice = false, requiresKey = true),
        )
    private val onDevice =
        FakeProvider(
            ProviderId.ON_DEVICE,
            ProviderCapability(8_000, streaming = true, onDevice = true, requiresKey = false),
        )

    private val registry = ProviderRegistry(listOf(claude, gemini, onDevice), vault)

    @AfterTest
    fun tearDown() {
        ProviderId.entries.forEach { vault.clear(it) }
    }

    @Test
    fun `provider lookup by id`() {
        assertEquals(claude, registry.provider(ProviderId.CLAUDE))
        assertEquals(gemini, registry.provider(ProviderId.GEMINI))
    }

    @Test
    fun `cloud provider is configured only with a key`() {
        assertFalse(registry.isConfigured(ProviderId.CLAUDE))
        vault.put(ProviderId.CLAUDE, "sk-x")
        assertTrue(registry.isConfigured(ProviderId.CLAUDE))
    }

    @Test
    fun `on-device provider needs no key`() {
        assertTrue(registry.isConfigured(ProviderId.ON_DEVICE))
    }

    @Test
    fun `configured lists only usable providers`() {
        vault.put(ProviderId.GEMINI, "g-key")
        val ids = registry.configured().map { it.id }
        assertTrue(ProviderId.GEMINI in ids)
        assertTrue(ProviderId.ON_DEVICE in ids)
        assertFalse(ProviderId.CLAUDE in ids)
    }

    @Test
    fun `unknown provider id resolves to null`() {
        val bare = ProviderRegistry(listOf(claude), vault)
        assertNull(bare.provider(ProviderId.GEMINI))
        assertFalse(bare.isConfigured(ProviderId.GEMINI))
    }
}
