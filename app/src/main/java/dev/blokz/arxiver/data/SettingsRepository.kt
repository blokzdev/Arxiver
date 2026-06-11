package dev.blokz.arxiver.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
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
    ) {
        private val syncIntervalKey = intPreferencesKey("sync_interval_hours")
        private val onboardedKey = booleanPreferencesKey("onboarded")

        val syncIntervalHours: Flow<Int> =
            context.dataStore.data.map { it[syncIntervalKey] ?: DEFAULT_SYNC_HOURS }

        val onboarded: Flow<Boolean> =
            context.dataStore.data.map { it[onboardedKey] ?: false }

        suspend fun setSyncIntervalHours(hours: Int) {
            context.dataStore.edit { it[syncIntervalKey] = hours }
        }

        suspend fun setOnboarded() {
            context.dataStore.edit { it[onboardedKey] = true }
        }

        companion object {
            const val DEFAULT_SYNC_HOURS = 6
            val SYNC_INTERVAL_CHOICES = listOf(3, 6, 12, 24)
        }
    }
