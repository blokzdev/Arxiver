package dev.blokz.arxiver.di

import dev.blokz.arxiver.core.ai.AiKeyStore
import dev.blokz.arxiver.core.ai.AnthropicProvider
import dev.blokz.arxiver.core.ai.GeminiProvider
import dev.blokz.arxiver.core.ai.GemmaEngine
import dev.blokz.arxiver.core.ai.InferenceTier
import dev.blokz.arxiver.core.ai.NanoEngine
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.core.ai.ProviderResolution
import dev.blokz.arxiver.core.ai.QwenEngine
import dev.blokz.arxiver.core.ai.StubNanoAvailability
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.ml.ModelDownloader
import dev.blokz.arxiver.data.AiProviderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression pin for the PA.3 Qwen-only bug (2026-07-03): executes the REAL `AppModule` `@Provides`
 * bodies (`onDeviceProvider` → `providerRegistry` → `providerResolver`) end-to-end — plain JUnit,
 * no Hilt/Robolectric (`AppModule` is a Kotlin object; the repo's only `@HiltAndroidTest` is
 * `@Ignore`d as order-flaky). The old seam (`gemmaEngine.isReady() || nanoEngine.isReady()`) made a
 * device with ONLY the Qwen light model resolve `NotConfigured`; this test cannot even compile
 * against that signature, and permanently prevents its return.
 *
 * Coverage boundary (by design): the Hilt qualifier routing (`@QwenModel` → the light downloader)
 * stays uncovered here — that wiring is exercised on-device (VERIFICATION §J/K).
 */
class AppModuleResolverWiringTest {
    private val dispatchers =
        object : DispatcherProvider {
            override val io = Dispatchers.Unconfined
            override val default = Dispatchers.Unconfined
            override val main = Dispatchers.Unconfined
        }

    private class NoKeys : AiKeyStore {
        override fun put(
            provider: ProviderId,
            key: String,
        ) = Unit

        override fun get(provider: ProviderId): String? = null

        override fun has(provider: ProviderId): Boolean = false

        override fun clear(provider: ProviderId) = Unit
    }

    private class FakeStore : AiProviderStore {
        override val selectedAiProvider: Flow<ProviderId?> = flowOf(null)

        override suspend fun setSelectedAiProvider(provider: ProviderId) = Unit

        override val preferredOnDeviceTier: Flow<InferenceTier?> = flowOf(null)

        override suspend fun setPreferredOnDeviceTier(tier: InferenceTier?) = Unit

        override val preferOnDeviceWhenReady: Flow<Boolean> = flowOf(false)

        override suspend fun setPreferOnDeviceWhenReady(prefer: Boolean) = Unit
    }

    /** The production composition, over temp model dirs — models "installed" by touching pinned files. */
    private fun resolverWith(
        gemmaPresent: Boolean,
        qwenPresent: Boolean,
    ): dev.blokz.arxiver.core.ai.ProviderResolver {
        val gemmaDir = Files.createTempDirectory("wiring-gemma").toFile()
        val qwenDir = Files.createTempDirectory("wiring-qwen").toFile()
        if (gemmaPresent) File(gemmaDir, GemmaEngine.SPEC.fileName).writeBytes(byteArrayOf(1))
        if (qwenPresent) File(qwenDir, QwenEngine.SPEC.fileName).writeBytes(byteArrayOf(1))

        val gemma =
            GemmaEngine(ModelDownloader(OkHttpClient(), dispatchers, gemmaDir, GemmaEngine.SPEC), dispatchers, gemmaDir)
        val qwen =
            QwenEngine(ModelDownloader(OkHttpClient(), dispatchers, qwenDir, QwenEngine.SPEC), dispatchers, qwenDir)
        val nano = NanoEngine(StubNanoAvailability(), dispatchers)
        val store = FakeStore()

        // The REAL @Provides bodies — the seams the PA.3 bug lived in.
        val onDevice = AppModule.onDeviceProvider(gemma, qwen, nano, dispatchers, store)
        val registry =
            AppModule.providerRegistry(
                anthropic = AnthropicProvider(OkHttpClient(), dispatchers, apiKey = { null }),
                gemini = GeminiProvider(OkHttpClient(), dispatchers, apiKey = { null }),
                onDevice = onDevice,
                aiKeyStore = NoKeys(),
            )
        return AppModule.providerResolver(registry, store, onDevice)
    }

    @Test
    fun `a Qwen-ONLY device resolves to on-device (the user-reported bug state)`() =
        runBlocking {
            val r = resolverWith(gemmaPresent = false, qwenPresent = true).resolve()
            assertEquals(ProviderId.ON_DEVICE, (r as ProviderResolution.Resolved).provider.id)
        }

    @Test
    fun `a Gemma-only device still resolves to on-device`() =
        runBlocking {
            val r = resolverWith(gemmaPresent = true, qwenPresent = false).resolve()
            assertEquals(ProviderId.ON_DEVICE, (r as ProviderResolution.Resolved).provider.id)
        }

    @Test
    fun `no models and no keys resolves NotConfigured`() =
        runBlocking {
            val r = resolverWith(gemmaPresent = false, qwenPresent = false).resolve()
            assertTrue(r is ProviderResolution.NotConfigured)
        }
}
