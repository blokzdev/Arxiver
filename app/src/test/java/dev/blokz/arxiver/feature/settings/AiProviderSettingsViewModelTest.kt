package dev.blokz.arxiver.feature.settings

import dev.blokz.arxiver.core.ai.AiKeyStore
import dev.blokz.arxiver.core.ai.AnthropicProvider
import dev.blokz.arxiver.core.ai.DeviceCapability
import dev.blokz.arxiver.core.ai.DeviceCapabilityProbe
import dev.blokz.arxiver.core.ai.GeminiProvider
import dev.blokz.arxiver.core.ai.InferenceTier
import dev.blokz.arxiver.core.ai.NanoAvailability
import dev.blokz.arxiver.core.ai.NanoDownloadProgress
import dev.blokz.arxiver.core.ai.NanoStatus
import dev.blokz.arxiver.core.ai.OnDeviceProvider
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.core.ai.ProviderRegistry
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.ml.ModelState
import dev.blokz.arxiver.data.AiProviderStore
import dev.blokz.arxiver.data.OnDeviceModelController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real-deps coverage (SPEC-AI-PROVIDERS §7): MockWebServer-backed providers
 * with in-memory key/selection stores, so this is a plain JVM test (the real
 * Keystore-backed [dev.blokz.arxiver.core.ai.AiKeyVault] is exercised in
 * `:core:ai`). `runBlocking` for the real IO (de-flake convention).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiProviderSettingsViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var keyStore: FakeKeyStore
    private lateinit var store: FakeStore
    private lateinit var registry: ProviderRegistry

    private val dispatchers =
        object : DispatcherProvider {
            override val io = Dispatchers.Unconfined
            override val default = Dispatchers.Unconfined
            override val main = Dispatchers.Unconfined
        }

    private class FakeKeyStore : AiKeyStore {
        private val keys = mutableMapOf<ProviderId, String>()

        override fun put(
            provider: ProviderId,
            key: String,
        ) {
            keys[provider] = key
        }

        override fun get(provider: ProviderId): String? = keys[provider]

        override fun has(provider: ProviderId): Boolean = keys.containsKey(provider)

        override fun clear(provider: ProviderId) {
            keys.remove(provider)
        }
    }

    private class FakeStore : AiProviderStore {
        val selected = MutableStateFlow<ProviderId?>(null)
        override val selectedAiProvider: Flow<ProviderId?> = selected

        override suspend fun setSelectedAiProvider(provider: ProviderId) {
            selected.value = provider
        }

        val preferred = MutableStateFlow<InferenceTier?>(null)
        override val preferredOnDeviceTier: Flow<InferenceTier?> = preferred

        override suspend fun setPreferredOnDeviceTier(tier: InferenceTier?) {
            preferred.value = tier
        }

        val preferOnDevice = MutableStateFlow(false)
        override val preferOnDeviceWhenReady: Flow<Boolean> = preferOnDevice

        override suspend fun setPreferOnDeviceWhenReady(prefer: Boolean) {
            preferOnDevice.value = prefer
        }
    }

    private class FakeNano(var nanoStatus: NanoStatus = NanoStatus.UNAVAILABLE) : NanoAvailability {
        override suspend fun status(): NanoStatus = nanoStatus

        override fun download(): Flow<NanoDownloadProgress> = flowOf(NanoDownloadProgress.Done)
    }

    private class FakeProbe(var ramMb: Long = 8192) : DeviceCapabilityProbe {
        var gemmaReady = false
        var lightReady = false
        var nano = NanoStatus.UNAVAILABLE
        var cloud = false

        override suspend fun probe() =
            DeviceCapability(
                totalRamMb = ramMb,
                nanoStatus = nano,
                gemmaReady = gemmaReady,
                lightReady = lightReady,
                cloudConfigured = cloud,
            )
    }

    private class FakeModelController : OnDeviceModelController {
        override val state = MutableStateFlow<ModelState>(ModelState.NotDownloaded)
        var downloads = 0
        var deletes = 0

        override fun download() {
            downloads++
        }

        override fun delete() {
            deletes++
            state.value = ModelState.NotDownloaded
        }
    }

    private lateinit var probe: FakeProbe
    private lateinit var modelController: FakeModelController
    private lateinit var lightController: FakeModelController
    private lateinit var nano: FakeNano

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
        keyStore = FakeKeyStore()
        store = FakeStore()
        probe = FakeProbe()
        modelController = FakeModelController()
        lightController = FakeModelController()
        nano = FakeNano()
        val client = OkHttpClient()
        val claude =
            AnthropicProvider(
                httpClient = client,
                dispatchers = dispatchers,
                apiKey = { keyStore.get(ProviderId.CLAUDE) },
                baseUrl = server.url("/v1").toString().trimEnd('/'),
            )
        val gemini =
            GeminiProvider(
                httpClient = client,
                dispatchers = dispatchers,
                apiKey = { keyStore.get(ProviderId.GEMINI) },
                baseUrl = server.url("/v1beta").toString().trimEnd('/'),
            )
        val onDevice = OnDeviceProvider(emptyList(), dispatchers)
        registry = ProviderRegistry(listOf(claude, gemini, onDevice), keyStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
    }

    private fun vm() =
        AiProviderSettingsViewModel(registry, keyStore, store, probe, modelController, lightController, nano)

    private fun claudeStream(): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                """data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"OK"}}""" + "\n\n" +
                    """data: {"type":"message_stop"}""" + "\n\n",
            )

    private fun row(
        state: AiProviderSettingsUiState,
        id: ProviderId,
    ): ProviderRow = state.rows.first { it.id == id }

    @Test
    fun `saving a key configures the provider and auto-selects it as default`() =
        runBlocking {
            val vm = vm()
            val job = launch(Dispatchers.Unconfined) { vm.uiState.collect {} }

            vm.saveKey(ProviderId.CLAUDE, "sk-claude")

            assertTrue(keyStore.has(ProviderId.CLAUDE))
            assertTrue(row(vm.uiState.value, ProviderId.CLAUDE).configured)
            assertEquals(ProviderId.CLAUDE, vm.uiState.value.selectedDefault)
            job.cancel()
        }

    @Test
    fun `second provider does not steal the default`() =
        runBlocking {
            val vm = vm()
            val job = launch(Dispatchers.Unconfined) { vm.uiState.collect {} }

            vm.saveKey(ProviderId.CLAUDE, "sk-claude")
            vm.saveKey(ProviderId.GEMINI, "g-key")

            assertEquals(ProviderId.CLAUDE, vm.uiState.value.selectedDefault)
            assertTrue(row(vm.uiState.value, ProviderId.GEMINI).configured)
            job.cancel()
        }

    @Test
    fun `clearing a key unconfigures the provider`() =
        runBlocking {
            val vm = vm()
            val job = launch(Dispatchers.Unconfined) { vm.uiState.collect {} }

            vm.saveKey(ProviderId.CLAUDE, "sk-claude")
            vm.clearKey(ProviderId.CLAUDE)

            assertFalse(keyStore.has(ProviderId.CLAUDE))
            assertFalse(row(vm.uiState.value, ProviderId.CLAUDE).configured)
            job.cancel()
        }

    @Test
    fun `the OpenAlex BYOK key round-trips (trimmed) and never becomes a chat default (PF4)`() =
        runBlocking {
            val vm = vm()
            val job = launch(Dispatchers.Unconfined) { vm.uiState.collect {} }

            vm.saveOpenAlexKey("  oa-secret  ")
            assertTrue(keyStore.has(ProviderId.OPENALEX))
            assertEquals("oa-secret", keyStore.get(ProviderId.OPENALEX), "key is trimmed")
            assertTrue(vm.uiState.value.openAlexKeyConfigured)
            // OpenAlex backs discovery/follows, not chat — it must not be picked as the chat default.
            assertNull(vm.uiState.value.selectedDefault)

            vm.clearOpenAlexKey()
            assertFalse(keyStore.has(ProviderId.OPENALEX))
            assertFalse(vm.uiState.value.openAlexKeyConfigured)
            job.cancel()
        }

    @Test
    fun `selecting a default persists it`() =
        runBlocking {
            val vm = vm()
            val job = launch(Dispatchers.Unconfined) { vm.uiState.collect {} }

            vm.selectDefault(ProviderId.GEMINI)

            assertEquals(ProviderId.GEMINI, vm.uiState.value.selectedDefault)
            assertEquals(ProviderId.GEMINI, store.selected.value)
            job.cancel()
        }

    @Test
    fun `test connection succeeds on a clean stream`() =
        runBlocking {
            server.enqueue(claudeStream())
            val vm = vm()
            val job = launch(Dispatchers.Unconfined) { vm.uiState.collect {} }
            vm.saveKey(ProviderId.CLAUDE, "sk-claude")

            vm.testConnection(ProviderId.CLAUDE)

            assertEquals(ConnectionTest.Success, row(vm.uiState.value, ProviderId.CLAUDE).test)
            job.cancel()
        }

    @Test
    fun `test connection reports auth failure on 401`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            val vm = vm()
            val job = launch(Dispatchers.Unconfined) { vm.uiState.collect {} }
            vm.saveKey(ProviderId.CLAUDE, "bad-key")

            vm.testConnection(ProviderId.CLAUDE)

            assertEquals(ConnectionTest.AuthFailed, row(vm.uiState.value, ProviderId.CLAUDE).test)
            job.cancel()
        }

    @Test
    fun `test connection reports offline on disconnect`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    .setBody("""data: {"type":"content_block_delta","delta":{"text":"x"}}"""),
            )
            val vm = vm()
            val job = launch(Dispatchers.Unconfined) { vm.uiState.collect {} }
            vm.saveKey(ProviderId.CLAUDE, "sk-claude")

            vm.testConnection(ProviderId.CLAUDE)

            assertEquals(ConnectionTest.Offline, row(vm.uiState.value, ProviderId.CLAUDE).test)
            job.cancel()
        }

    @Test
    fun `on-device row reflects capability and model state`() =
        runBlocking {
            probe.ramMb = 7900
            probe.cloud = true
            modelController.state.value = ModelState.Downloading(42)
            val vm = vm()
            val job = launch(Dispatchers.Unconfined) { vm.uiState.collect {} }

            val onDevice = row(vm.uiState.value, ProviderId.ON_DEVICE).onDevice!!
            assertEquals(7900, onDevice.totalRamMb)
            assertTrue(onDevice.gemmaEligible)
            assertEquals(ModelState.Downloading(42), onDevice.gemmaState)
            // Cloud key set but no on-device model → recommends CLOUD.
            assertEquals(dev.blokz.arxiver.core.ai.InferenceTier.CLOUD, onDevice.recommendedTier)
            job.cancel()
        }

    @Test
    fun `low-ram device is not gemma-eligible`() =
        runBlocking {
            probe.ramMb = 2048
            val vm = vm()
            val job = launch(Dispatchers.Unconfined) { vm.uiState.collect {} }

            assertFalse(row(vm.uiState.value, ProviderId.ON_DEVICE).onDevice!!.gemmaEligible)
            job.cancel()
        }

    @Test
    fun `download and delete drive the gemma model controller`() =
        runBlocking {
            val vm = vm()
            val job = launch(Dispatchers.Unconfined) { vm.uiState.collect {} }

            vm.downloadModel(InferenceTier.GEMMA)
            vm.deleteModel(InferenceTier.GEMMA)

            assertEquals(1, modelController.downloads)
            assertEquals(1, modelController.deletes)
            assertEquals(0, lightController.downloads) // the light controller is untouched
            job.cancel()
        }

    @Test
    fun `download and delete for the LIGHT tier drive the light controller, not gemma`() =
        runBlocking {
            val vm = vm()
            val job = launch(Dispatchers.Unconfined) { vm.uiState.collect {} }

            vm.downloadModel(InferenceTier.LIGHT)
            vm.deleteModel(InferenceTier.LIGHT)

            assertEquals(1, lightController.downloads)
            assertEquals(1, lightController.deletes)
            assertEquals(0, modelController.downloads) // gemma untouched
            job.cancel()
        }

    @Test
    fun `light model state and eligibility surface in the on-device row`() =
        runBlocking {
            probe.ramMb = 3500 // light-eligible (>=3 GB) but not gemma-eligible (<4 GB)
            val vm = vm()
            val job = launch(Dispatchers.Unconfined) { vm.uiState.collect {} }

            lightController.state.value = ModelState.Downloading(30)
            val onDevice = row(vm.uiState.value, ProviderId.ON_DEVICE).onDevice!!
            assertTrue(onDevice.lightEligible)
            assertFalse(onDevice.gemmaEligible)
            assertEquals(ModelState.Downloading(30), onDevice.lightState)
            job.cancel()
        }

    @Test
    fun `setting preferred on-device tier persists and surfaces in the row`() =
        runBlocking {
            probe.gemmaReady = true
            probe.nano = NanoStatus.AVAILABLE
            modelController.state.value = ModelState.Ready(java.io.File("m.litertlm"))
            val vm = vm()
            val job = launch(Dispatchers.Unconfined) { vm.uiState.collect {} }

            vm.setPreferredOnDeviceTier(InferenceTier.NANO)

            assertEquals(InferenceTier.NANO, store.preferred.value)
            assertEquals(InferenceTier.NANO, row(vm.uiState.value, ProviderId.ON_DEVICE).onDevice!!.preferredTier)
            job.cancel()
        }

    @Test
    fun `setting prefer-on-device-when-ready persists and surfaces in the row`() =
        runBlocking {
            probe.gemmaReady = true
            modelController.state.value = ModelState.Ready(java.io.File("m.litertlm"))
            val vm = vm()
            val job = launch(Dispatchers.Unconfined) { vm.uiState.collect {} }

            vm.setPreferOnDeviceWhenReady(true)

            assertTrue(store.preferOnDevice.value)
            assertTrue(row(vm.uiState.value, ProviderId.ON_DEVICE).onDevice!!.preferOnDeviceWhenReady)
            job.cancel()
        }
}
