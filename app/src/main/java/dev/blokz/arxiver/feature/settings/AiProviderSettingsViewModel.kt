package dev.blokz.arxiver.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.blokz.arxiver.core.ai.AiException
import dev.blokz.arxiver.core.ai.AiKeyStore
import dev.blokz.arxiver.core.ai.ChatMessage
import dev.blokz.arxiver.core.ai.ChatRequest
import dev.blokz.arxiver.core.ai.ChatRole
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.core.ai.ProviderRegistry
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.data.AiProviderStore
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

data class ProviderRow(
    val id: ProviderId,
    val configured: Boolean,
    val test: ConnectionTest = ConnectionTest.Idle,
)

data class AiProviderSettingsUiState(
    val rows: List<ProviderRow> = emptyList(),
    val selectedDefault: ProviderId? = null,
)

/**
 * "AI providers" settings (SPEC-AI-PROVIDERS §4). Connect a BYOK key per
 * provider, test it, and pick the default. Keys flow straight into the
 * [AiKeyVault] and are never read back here — the UI only learns whether a key
 * is present (`configured`). A test connection fires a tiny prompt through the
 * provider, so it does reach the network with the user's key (the only data
 * sent in P1.1 — no paper content; that gate arrives with the P2 chat path).
 */
@HiltViewModel
class AiProviderSettingsViewModel
    @Inject
    constructor(
        private val registry: ProviderRegistry,
        private val keyStore: AiKeyStore,
        private val store: AiProviderStore,
    ) : ViewModel() {
        private val testStates = MutableStateFlow<Map<ProviderId, ConnectionTest>>(emptyMap())

        // Bumped whenever a key is stored/cleared so the configured flags recompute
        // (the vault is a synchronous read, not a Flow).
        private val configuredRevision = MutableStateFlow(0)

        val uiState: StateFlow<AiProviderSettingsUiState> =
            combine(testStates, configuredRevision, store.selectedAiProvider) { tests, _, selected ->
                AiProviderSettingsUiState(
                    rows =
                        registry.all().map { provider ->
                            ProviderRow(
                                id = provider.id,
                                configured = registry.isConfigured(provider.id),
                                test = tests[provider.id] ?: ConnectionTest.Idle,
                            )
                        },
                    selectedDefault = selected,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiProviderSettingsUiState())

        fun saveKey(
            id: ProviderId,
            key: String,
        ) {
            if (key.isBlank()) return
            keyStore.put(id, key.trim())
            testStates.update { it - id }
            configuredRevision.update { it + 1 }
            // First provider connected becomes the default automatically.
            viewModelScope.launch {
                if (store.selectedAiProvider.first() == null) store.setSelectedAiProvider(id)
            }
        }

        fun clearKey(id: ProviderId) {
            keyStore.clear(id)
            testStates.update { it - id }
            configuredRevision.update { it + 1 }
        }

        fun selectDefault(id: ProviderId) {
            viewModelScope.launch { store.setSelectedAiProvider(id) }
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
