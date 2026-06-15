package dev.blokz.arxiver.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.core.ai.ProviderRegistry
import dev.blokz.arxiver.core.ai.TierSelector
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.ml.ModelState
import dev.blokz.arxiver.data.AiProviderStore
import dev.blokz.arxiver.data.OnDeviceModelController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    val nanoStatus: NanoStatus,
    val preferredTier: InferenceTier? = null,
    val nanoDownload: NanoDownloadProgress? = null,
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
)

/**
 * "AI providers" settings (SPEC-AI-PROVIDERS §4). Cloud providers: connect a
 * BYOK key (write-only), test, pick the default. On-device (P1.2): shows device
 * capability + the recommended tier, drives the Gemma download/delete and the
 * Nano enable, and lets the user pick the preferred on-device engine when more
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
        private val modelController: OnDeviceModelController,
        private val nanoAvailability: NanoAvailability,
    ) : ViewModel() {
        private val testStates = MutableStateFlow<Map<ProviderId, ConnectionTest>>(emptyMap())

        // Bumped whenever a key is stored/cleared so the configured flags recompute.
        private val configuredRevision = MutableStateFlow(0)

        private val capability = MutableStateFlow<DeviceCapability?>(null)
        private val nanoDownload = MutableStateFlow<NanoDownloadProgress?>(null)

        private val onDeviceInfo: Flow<OnDeviceInfo?> =
            combine(
                modelController.state,
                capability,
                store.preferredOnDeviceTier,
                nanoDownload,
            ) { gemmaState, deviceCapability, preferred, download ->
                deviceCapability?.let {
                    OnDeviceInfo(
                        recommendedTier = TierSelector.recommend(it),
                        totalRamMb = it.totalRamMb,
                        gemmaEligible = it.gemmaEligible,
                        gemmaState = gemmaState,
                        nanoStatus = it.nanoStatus,
                        preferredTier = preferred,
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
                AiProviderSettingsUiState(
                    rows =
                        registry.all().map { provider ->
                            ProviderRow(
                                id = provider.id,
                                configured = registry.isConfigured(provider.id),
                                test = tests[provider.id] ?: ConnectionTest.Idle,
                                onDevice = if (provider.id == ProviderId.ON_DEVICE) onDevice else null,
                            )
                        },
                    selectedDefault = selected,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiProviderSettingsUiState())

        init {
            // Re-probe device capability whenever the model state changes (download
            // progress / completion / deletion), which also seeds the initial value.
            viewModelScope.launch { modelController.state.collect { refreshCapability() } }
        }

        fun saveKey(
            id: ProviderId,
            key: String,
        ) {
            if (key.isBlank()) return
            keyStore.put(id, key.trim())
            testStates.update { it - id }
            configuredRevision.update { it + 1 }
            viewModelScope.launch {
                if (store.selectedAiProvider.first() == null) store.setSelectedAiProvider(id)
                refreshCapability()
            }
        }

        fun clearKey(id: ProviderId) {
            keyStore.clear(id)
            testStates.update { it - id }
            configuredRevision.update { it + 1 }
            viewModelScope.launch { refreshCapability() }
        }

        fun selectDefault(id: ProviderId) {
            viewModelScope.launch { store.setSelectedAiProvider(id) }
        }

        fun setPreferredOnDeviceTier(tier: InferenceTier?) {
            viewModelScope.launch { store.setPreferredOnDeviceTier(tier) }
        }

        fun downloadOnDeviceModel() = modelController.download()

        fun deleteOnDeviceModel() {
            modelController.delete()
            viewModelScope.launch { refreshCapability() }
        }

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

        fun testConnection(id: ProviderId) {
            val provider = registry.provider(id) ?: return
            if (testStates.value[id] == ConnectionTest.Testing) return
            viewModelScope.launch {
                setTest(id, ConnectionTest.Testing)
                val result =
                    runCatching {
                        provider
                            .chat(
                                ChatRequest(
                                    messages = listOf(ChatMessage(ChatRole.USER, PING_PROMPT)),
                                    maxTokens = PING_TOKENS,
                                ),
                            ).collect { /* drain the stream; success = no error */ }
                    }
                setTest(
                    id,
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
            id: ProviderId,
            state: ConnectionTest,
        ) = testStates.update { it + (id to state) }

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
