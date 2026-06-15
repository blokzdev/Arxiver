package dev.blokz.arxiver.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.blokz.arxiver.core.ai.InferenceTier
import dev.blokz.arxiver.core.ai.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "arxiver_settings")

@Singleton
class SettingsRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AiProviderStore {
        private val syncIntervalKey = intPreferencesKey("sync_interval_hours")
        private val onboardedKey = booleanPreferencesKey("onboarded")
        private val selectedAiProviderKey = stringPreferencesKey("selected_ai_provider")
        private val preferredOnDeviceTierKey = stringPreferencesKey("preferred_ondevice_tier")

        val syncIntervalHours: Flow<Int> =
            context.dataStore.data.map { it[syncIntervalKey] ?: DEFAULT_SYNC_HOURS }

        val onboarded: Flow<Boolean> =
            context.dataStore.data.map { it[onboardedKey] ?: false }

        /** The user's default AI provider (P1 BYOK); null until one is chosen. */
        override val selectedAiProvider: Flow<ProviderId?> =
            context.dataStore.data.map { prefs ->
                prefs[selectedAiProviderKey]?.let { name ->
                    runCatching { ProviderId.valueOf(name) }.getOrNull()
                }
            }

        suspend fun setSyncIntervalHours(hours: Int) {
            context.dataStore.edit { it[syncIntervalKey] = hours }
        }

        suspend fun setOnboarded() {
            context.dataStore.edit { it[onboardedKey] = true }
        }

        override suspend fun setSelectedAiProvider(provider: ProviderId) {
            context.dataStore.edit { it[selectedAiProviderKey] = provider.name }
        }

        override val preferredOnDeviceTier: Flow<InferenceTier?> =
            context.dataStore.data.map { prefs ->
                prefs[preferredOnDeviceTierKey]?.let { name ->
                    runCatching { InferenceTier.valueOf(name) }.getOrNull()
                }
            }

        override suspend fun setPreferredOnDeviceTier(tier: InferenceTier?) {
            context.dataStore.edit { prefs ->
                if (tier == null) {
                    prefs.remove(
                        preferredOnDeviceTierKey,
                    )
                } else {
                    prefs[preferredOnDeviceTierKey] = tier.name
                }
            }
        }

        companion object {
            const val DEFAULT_SYNC_HOURS = 6
            val SYNC_INTERVAL_CHOICES = listOf(3, 6, 12, 24)
        }
    }
