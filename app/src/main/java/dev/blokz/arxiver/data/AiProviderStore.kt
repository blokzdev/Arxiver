package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.ai.InferenceTier
import dev.blokz.arxiver.core.ai.ProviderId
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for the user's AI selections (P1). Narrow interface so the
 * AI-provider settings ViewModel depends only on this, not the whole
 * [SettingsRepository] — and tests can substitute an in-memory fake.
 */
interface AiProviderStore {
    val selectedAiProvider: Flow<ProviderId?>

    suspend fun setSelectedAiProvider(provider: ProviderId)

    /** Preferred on-device engine when more than one is ready (null = auto / default order). */
    val preferredOnDeviceTier: Flow<InferenceTier?>

    suspend fun setPreferredOnDeviceTier(tier: InferenceTier?)

    /**
     * Prefer an on-device engine for chat whenever one is ready, over the selected
     * cloud provider (default false = respect the explicit selection). The
     * privacy/cost opt-in surfaced in Settings; consumed by `ProviderResolver`.
     */
    val preferOnDeviceWhenReady: Flow<Boolean>

    suspend fun setPreferOnDeviceWhenReady(prefer: Boolean)
}
