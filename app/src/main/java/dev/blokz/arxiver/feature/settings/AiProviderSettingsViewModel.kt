package dev.blokz.arxiver.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.ai.AiException
import dev.blokz.arxiver.core.ai.AiKeyStore
import dev.blokz.arxiver.core.ai.ChatMessage
import dev.blokz.arxiver.core.ai.ChatRequest
import dev.blokz.arxiver.core.ai.ChatRole
import dev.blokz.arxiver.core.ai.DeviceCapability
import dev.blokz.arxiver.core.ai.DeviceCapabilityProbe
import dev.blokz.arxiver.core.ai.InferenceTier
import dev.blokz.arxiver.core.ai.NanoAvailability
import dev.blokz.arxiver.core.ai.NanoDownloadProgress
import dev.blokz.arxiver.core.ai.NanoStatus
import dev.blokz.arxiver.core.ai.OnDeviceProvider
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.core.ai.ProviderRegistry
import dev.blokz.arxiver.core.ai.TierSelector
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.ml.ModelState
import dev.blokz.arxiver.data.AiProviderStore
import dev.blokz.arxiver.data.OnDeviceModelController
import dev.blokz.arxiver.di.GemmaModel
import dev.blokz.arxiver.di.QwenModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Outcome of a "Test connection" ping against a provider. */
enum class ConnectionTest { Idle, Testing, Success, AuthFailed, Offline, Error }

/** On-device tier detail for the `ON_DEVICE` row (null while still probing). */
data class OnDeviceInfo(
    val recommendedTier: InferenceTier,
    val totalRamMb: Long,
    val gemmaEligible: Boolean,
    val gemmaState: ModelState,
    val lightEligible: Boolean,
    val lightState: ModelState,
    val nanoStatus: NanoStatus,
    val preferredTier: InferenceTier? = null,
    val preferOnDeviceWhenReady: Boolean = false,
    val nanoDownload: NanoDownloadProgress? = null,
    /** Per-model Test results (PA.6 follow-up) — rendered inside each ModelCard, not a shared row. */
    val tierTests: Map<InferenceTier, ConnectionTest> = emptyMap(),
)

data class ProviderRow(
    val id: ProviderId,
    val configured: Boolean,
    val test: ConnectionTest = ConnectionTest.Idle,
    val onDevice: OnDeviceInfo? = null,
)

data class AiProviderSettingsUiState(
    val rows: List<ProviderRow> = emptyList(),
    val selectedDefault: ProviderId? = null,
    /** Whether an optional Semantic Scholar BYOK key is set (P-Tools PT.3). S2 is NOT a chat provider,
     *  so it has no [ProviderRow]; this drives a standalone optional-key card. Write-only — the key
     *  itself is never read back to the UI. */
    val s2KeyConfigured: Boolean = false,
    /** Whether an optional OpenAlex BYOK key is set (P-Feeds PF.4). Like S2, OpenAlex backs discovery/follows,
     *  not chat — a standalone optional-key card, write-only. */
    val openAlexKeyConfigured: Boolean = false,
)

/**
 * "AI providers" settings (SPEC-AI-PROVIDERS §4). Cloud providers: connect a
 * BYOK key (write-only), test, pick the default. On-device (P1.2): shows device
 * capability + the recommended tier, drives the Gemma and Qwen-light download/delete
 * and the Nano enable, and lets the user pick the preferred on-device engine when more
 * than one is ready. A test connection runs entirely locally for on-device.
 */
@HiltViewModel
class AiProviderSettingsViewModel
    @Inject
    constructor(
        private val registry: ProviderRegistry,
        private val keyStore: AiKeyStore,
        private val store: AiProviderStore,
        private val capabilityProbe: DeviceCapabilityProbe,
        @GemmaModel private val modelController: OnDeviceModelController,
        @QwenModel private val lightController: OnDeviceModelController,
        private val nanoAvailability: NanoAvailability,
    ) : ViewModel() {
        // Keyed by (provider, on-device tier) — tier=null for cloud providers' shared row. A single
        // ProviderId key let the Qwen card's result render as the row's (possibly Gemma-served) one.
        private val testStates = MutableStateFlow<Map<Pair<ProviderId, InferenceTier?>, ConnectionTest>>(emptyMap())

        /** One-shot "model ready" snackbar events (the @StringRes engine name); PA.6 follow-up. */
        private val _modelReadyEvents = Channel<Int>(Channel.BUFFERED)
        val modelReadyEvents = _modelReadyEvents.receiveAsFlow()

        // Bumped whenever a key is stored/cleared so the configured flags recompute.
        private val configuredRevision = MutableStateFlow(0)

        private val capability = MutableStateFlow<DeviceCapability?>(null)
        private val nanoDownload = MutableStateFlow<NanoDownloadProgress?>(null)

        // Pre-combine the two model-download states so the outer combine stays within its 5-arg
        // typed overload (Gemma + the light Qwen tier; P-Atlas PA.3b).
        private val modelStates: Flow<Pair<ModelState, ModelState>> =
            combine(modelController.state, lightController.state) { gemma, light -> gemma to light }

        private val onDeviceInfo: Flow<OnDeviceInfo?> =
            combine(
                modelStates,
                capability,
                store.preferredOnDeviceTier,
                store.preferOnDeviceWhenReady,
                nanoDownload,
            ) { (gemmaState, lightState), deviceCapability, preferred, preferOnDevice, download ->
                deviceCapability?.let {
                    OnDeviceInfo(
                        recommendedTier = TierSelector.recommend(it),
                        totalRamMb = it.totalRamMb,
                        gemmaEligible = it.gemmaEligible,
                        gemmaState = gemmaState,
                        lightEligible = it.lightEligible,
                        lightState = lightState,
                        nanoStatus = it.nanoStatus,
                        preferredTier = preferred,
                        preferOnDeviceWhenReady = preferOnDevice,
                        nanoDownload = download,
                    )
                }
            }

        val uiState: StateFlow<AiProviderSettingsUiState> =
            combine(
                testStates,
                configuredRevision,
                store.selectedAiProvider,
                onDeviceInfo,
            ) { tests, _, selected, onDevice ->
                val tierTests =
                    tests
                        .filterKeys { (id, tier) -> id == ProviderId.ON_DEVICE && tier != null }
                        .map { (key, value) -> key.second!! to value }
                        .toMap()
                AiProviderSettingsUiState(
                    rows =
                        registry.all().map { provider ->
                            ProviderRow(
                                id = provider.id,
                                configured = registry.isConfigured(provider.id),
                                test = tests[provider.id to null] ?: ConnectionTest.Idle,
                                onDevice =
                                    if (provider.id == ProviderId.ON_DEVICE) {
                                        onDevice?.copy(tierTests = tierTests)
                                    } else {
                                        null
                                    },
                            )
                        },
                    selectedDefault = selected,
                    // Recomputed on each configuredRevision bump (saveS2Key/clearS2Key), like the rows.
                    s2KeyConfigured = keyStore.has(ProviderId.SEMANTIC_SCHOLAR),
                    openAlexKeyConfigured = keyStore.has(ProviderId.OPENALEX),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiProviderSettingsUiState())

        init {
            // Re-probe device capability whenever a model state changes (download
            // progress / completion / deletion), which also seeds the initial value.
            // A Downloading→Ready edge additionally fires the "model ready" snackbar (PA.6 follow-up
            // — the reporting user had zero in-app completion signal after a 614 MB download).
            viewModelScope.launch { watchModel(modelController, R.string.ai_engine_gemma) }
            viewModelScope.launch { watchModel(lightController, R.string.ai_engine_light) }
        }

        private suspend fun watchModel(
            controller: OnDeviceModelController,
            nameRes: Int,
        ) {
            var previous: ModelState? = null
            controller.state.collect { state ->
                if (previous is ModelState.Downloading && state is ModelState.Ready) {
                    _modelReadyEvents.send(nameRes)
                }
                previous = state
                refreshCapability()
            }
        }

        fun saveKey(
            id: ProviderId,
            key: String,
        ) {
            if (key.isBlank()) return
            keyStore.put(id, key.trim())
            testStates.update { states -> states.filterKeys { it.first != id } }
            configuredRevision.update { it + 1 }
            viewModelScope.launch {
                if (store.selectedAiProvider.first() == null) store.setSelectedAiProvider(id)
                refreshCapability()
            }
        }

        fun clearKey(id: ProviderId) {
            keyStore.clear(id)
            testStates.update { states -> states.filterKeys { it.first != id } }
            configuredRevision.update { it + 1 }
            viewModelScope.launch { refreshCapability() }
        }

        /**
         * Save/clear the OPTIONAL Semantic Scholar BYOK key (P-Tools PT.3). Unlike [saveKey], S2 is not a
         * chat provider: it never becomes the selected default, has no "Test connection", and does not
         * affect device capability — so this only writes the vault + bumps the configured revision. The
         * key lives solely in EncryptedSharedPreferences and is never read back to the UI (write-only).
         */
        fun saveS2Key(key: String) {
            if (key.isBlank()) return
            keyStore.put(ProviderId.SEMANTIC_SCHOLAR, key.trim())
            configuredRevision.update { it + 1 }
        }

        fun clearS2Key() {
            keyStore.clear(ProviderId.SEMANTIC_SCHOLAR)
            configuredRevision.update { it + 1 }
        }

        /**
         * Save/clear the OPTIONAL OpenAlex BYOK key (P-Feeds PF.4). Like [saveS2Key], OpenAlex is not a chat
         * provider — it backs discovery/follows — so this only writes the vault + bumps the configured revision;
         * the key lives solely in EncryptedSharedPreferences and is never read back to the UI (write-only).
         */
        fun saveOpenAlexKey(key: String) {
            if (key.isBlank()) return
            keyStore.put(ProviderId.OPENALEX, key.trim())
            configuredRevision.update { it + 1 }
        }

        fun clearOpenAlexKey() {
            keyStore.clear(ProviderId.OPENALEX)
            configuredRevision.update { it + 1 }
        }

        fun selectDefault(id: ProviderId) {
            viewModelScope.launch { store.setSelectedAiProvider(id) }
        }

        fun setPreferredOnDeviceTier(tier: InferenceTier?) {
            viewModelScope.launch { store.setPreferredOnDeviceTier(tier) }
        }

        fun setPreferOnDeviceWhenReady(prefer: Boolean) {
            viewModelScope.launch { store.setPreferOnDeviceWhenReady(prefer) }
        }

        /** Download the model for [tier] (GEMMA or the light Qwen LIGHT tier; P-Atlas PA.3b). */
        fun downloadModel(tier: InferenceTier) = controllerFor(tier).download()

        fun deleteModel(tier: InferenceTier) {
            controllerFor(tier).delete()
            viewModelScope.launch { refreshCapability() }
        }

        private fun controllerFor(tier: InferenceTier): OnDeviceModelController =
            if (tier == InferenceTier.LIGHT) lightController else modelController

        fun downloadNano() {
            viewModelScope.launch {
                nanoAvailability.download().collect { progress ->
                    nanoDownload.value = progress
                    if (progress is NanoDownloadProgress.Done) {
                        refreshCapability()
                        nanoDownload.value = null
                    }
                }
            }
        }

        /**
         * Ping the provider; for the on-device row a non-null [tier] PINS the test to that model's
         * engine (PA.6 follow-up) — before this, the Qwen card's Test streamed whatever engine the
         * default order picked (usually Gemma), so a broken light build still showed Success.
         */
        fun testConnection(
            id: ProviderId,
            tier: InferenceTier? = null,
        ) {
            val provider = registry.provider(id) ?: return
            val key = id to tier
            if (testStates.value[key] == ConnectionTest.Testing) return
            viewModelScope.launch {
                setTest(key, ConnectionTest.Testing)
                val request =
                    ChatRequest(
                        messages = listOf(ChatMessage(ChatRole.USER, PING_PROMPT)),
                        maxTokens = PING_TOKENS,
                    )
                val stream =
                    if (tier != null && provider is OnDeviceProvider) {
                        provider.chat(request, pinTier = tier)
                    } else {
                        provider.chat(request)
                    }
                val result =
                    runCatching {
                        stream.collect { /* drain the stream; success = no error */ }
                    }
                setTest(
                    key,
                    result.fold(
                        onSuccess = { ConnectionTest.Success },
                        onFailure = { (it as? AiException)?.error.toConnectionTest() },
                    ),
                )
            }
        }

        private suspend fun refreshCapability() {
            capability.value = capabilityProbe.probe()
        }

        private fun setTest(
            key: Pair<ProviderId, InferenceTier?>,
            state: ConnectionTest,
        ) = testStates.update { it + (key to state) }

        private fun AppError?.toConnectionTest(): ConnectionTest =
            when (this) {
                is AppError.Upstream -> if (httpCode in AUTH_CODES) ConnectionTest.AuthFailed else ConnectionTest.Error
                AppError.Offline -> ConnectionTest.Offline
                else -> ConnectionTest.Error
            }

        companion object {
            private const val PING_PROMPT = "Reply with OK."
            private const val PING_TOKENS = 16

            // A minimal valid ping only fails on auth for these codes (Anthropic 401/403;
            // Gemini returns 400 API_KEY_INVALID), so they map to "auth failed".
            private val AUTH_CODES = setOf(400, 401, 403)
        }
    }
