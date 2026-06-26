package dev.blokz.arxiver.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderResolverTest {
    private class FakeProvider(
        override val id: ProviderId,
        requiresKey: Boolean,
    ) : AiProvider {
        override val capability =
            ProviderCapability(
                contextTokens = 1000,
                streaming = true,
                onDevice = !requiresKey,
                requiresKey = requiresKey,
                richness = if (requiresKey) OutputRichness.FULL else OutputRichness.PLAIN,
            )

        override fun chat(request: ChatRequest): Flow<ChatChunk> = emptyFlow()
    }

    private class FakeKeyStore(private val keys: MutableSet<ProviderId> = mutableSetOf()) : AiKeyStore {
        override fun put(
            provider: ProviderId,
            key: String,
        ) {
            keys += provider
        }

        override fun get(provider: ProviderId): String? = if (provider in keys) "k" else null

        override fun has(provider: ProviderId): Boolean = provider in keys

        override fun clear(provider: ProviderId) {
            keys -= provider
        }
    }

    private val claude = FakeProvider(ProviderId.CLAUDE, requiresKey = true)
    private val gemini = FakeProvider(ProviderId.GEMINI, requiresKey = true)
    private val onDevice = FakeProvider(ProviderId.ON_DEVICE, requiresKey = false)

    private fun registry(
        providers: List<AiProvider> = listOf(claude, gemini, onDevice),
        keys: Set<ProviderId> = setOf(ProviderId.CLAUDE),
    ) = ProviderRegistry(providers, FakeKeyStore(keys.toMutableSet()))

    private fun resolver(
        registry: ProviderRegistry = registry(),
        selected: ProviderId? = ProviderId.CLAUDE,
        preferOnDevice: Boolean = false,
        onDeviceReady: Boolean = true,
    ) = ProviderResolver(registry, { selected }, { preferOnDevice }, { onDeviceReady })

    @Test
    fun `respects the explicit cloud selection by default even when on-device is ready`() =
        runTest {
            val r = resolver(selected = ProviderId.CLAUDE, preferOnDevice = false, onDeviceReady = true).resolve()
            assertEquals(ProviderId.CLAUDE, (r as ProviderResolution.Resolved).provider.id)
        }

    @Test
    fun `prefer-on-device toggle wins over the selected cloud provider when ready`() =
        runTest {
            val r = resolver(selected = ProviderId.CLAUDE, preferOnDevice = true, onDeviceReady = true).resolve()
            assertEquals(ProviderId.ON_DEVICE, (r as ProviderResolution.Resolved).provider.id)
        }

    @Test
    fun `prefer-on-device falls through to selection when no engine is ready`() =
        runTest {
            val r = resolver(selected = ProviderId.CLAUDE, preferOnDevice = true, onDeviceReady = false).resolve()
            assertEquals(ProviderId.CLAUDE, (r as ProviderResolution.Resolved).provider.id)
        }

    @Test
    fun `unconfigured selection falls back to a configured provider, on-device first`() =
        runTest {
            // GEMINI selected but no key; on-device ready → on-device wins the fallback.
            val r = resolver(selected = ProviderId.GEMINI, onDeviceReady = true).resolve()
            assertEquals(ProviderId.ON_DEVICE, (r as ProviderResolution.Resolved).provider.id)
        }

    @Test
    fun `selected on-device is used only when an engine is ready`() =
        runTest {
            val notReady =
                resolver(
                    selected = ProviderId.ON_DEVICE,
                    onDeviceReady = false,
                    registry = registry(keys = emptySet()),
                ).resolve()
            assertTrue(notReady is ProviderResolution.NotConfigured)

            val ready = resolver(selected = ProviderId.ON_DEVICE, onDeviceReady = true).resolve()
            assertEquals(ProviderId.ON_DEVICE, (ready as ProviderResolution.Resolved).provider.id)
        }

    @Test
    fun `nothing usable resolves to NotConfigured`() =
        runTest {
            val r =
                resolver(
                    registry = registry(keys = emptySet()),
                    selected = ProviderId.CLAUDE,
                    onDeviceReady = false,
                ).resolve()
            assertTrue(r is ProviderResolution.NotConfigured)
        }
}
