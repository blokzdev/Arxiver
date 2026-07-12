package dev.blokz.arxiver.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
        private val preferOnDeviceWhenReadyKey = booleanPreferencesKey("prefer_ondevice_when_ready")
        private val digestEnabledKey = booleanPreferencesKey("digest_enabled")
        private val lastDigestPostedAtKey = longPreferencesKey("last_digest_posted_at")

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

        override val preferOnDeviceWhenReady: Flow<Boolean> =
            context.dataStore.data.map { it[preferOnDeviceWhenReadyKey] ?: false }

        override suspend fun setPreferOnDeviceWhenReady(prefer: Boolean) {
            context.dataStore.edit { it[preferOnDeviceWhenReadyKey] = prefer }
        }

        /** Ambient digest opt-in (P-Ambient PA.1b); default OFF — the digest never fires until the user flips it. */
        val digestEnabled: Flow<Boolean> =
            context.dataStore.data.map { it[digestEnabledKey] ?: false }

        suspend fun setDigestEnabled(enabled: Boolean) {
            context.dataStore.edit { it[digestEnabledKey] = enabled }
        }

        /** When the last digest was actually posted (the daily-cap cursor); 0 = never. Kept distinct from the
         * per-row `digested_at` so a lost cursor write can't also defeat the fatigue cap. */
        val lastDigestPostedAt: Flow<Long> =
            context.dataStore.data.map { it[lastDigestPostedAtKey] ?: 0L }

        suspend fun setLastDigestPostedAt(atMillis: Long) {
            context.dataStore.edit { it[lastDigestPostedAtKey] = atMillis }
        }

        companion object {
            const val DEFAULT_SYNC_HOURS = 6
            val SYNC_INTERVAL_CHOICES = listOf(3, 6, 12, 24)

            /** Fatigue cap: at most one digest per this window even at a 3h sync cadence (P-Ambient PA.1b). */
            const val DIGEST_MIN_INTERVAL_MS = 20L * 60 * 60 * 1000 // 20h ⇒ effectively once/day

            /** Only recently-arrived rows can be announced as "new" (a weeks-old row crossing a shifted cut isn't). */
            const val DIGEST_RECENCY_WINDOW_MS = 14L * 24 * 60 * 60 * 1000
        }
    }
