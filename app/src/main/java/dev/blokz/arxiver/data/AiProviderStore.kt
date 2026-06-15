package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.ai.ProviderId
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for the user's default AI provider (P1 BYOK). Narrow interface so
 * the AI-provider settings ViewModel depends only on this, not the whole
 * [SettingsRepository] — and tests can substitute an in-memory fake.
 */
interface AiProviderStore {
    val selectedAiProvider: Flow<ProviderId?>

    suspend fun setSelectedAiProvider(provider: ProviderId)
}
