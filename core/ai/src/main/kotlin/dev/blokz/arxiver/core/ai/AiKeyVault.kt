package dev.blokz.arxiver.core.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keystore-backed storage for BYOK cloud provider API keys
 * (SPEC-AI-PROVIDERS §4). Mirrors [dev.blokz.arxiver.core.claude.TokenVault]'s
 * crypto (AES256-GCM/SIV) but is **provider-keyed** — one slot per
 * [ProviderId], in its own prefs file.
 *
 * Red lines (CLAUDE.md): keys live only here — never in the DB, logs, exports,
 * backups, or fixtures, and are never read back to the UI (entry is
 * write-only; the UI only learns whether a key [has] been set).
 */
class AiKeyVault(context: Context) : AiKeyStore {
    private val prefs: SharedPreferences by lazy {
        val masterKey =
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun put(
        provider: ProviderId,
        key: String,
    ) {
        prefs.edit().putString(provider.name, key).apply()
    }

    /** Null when unset or undecryptable (e.g. restored to a new device). */
    override fun get(provider: ProviderId): String? = runCatching { prefs.getString(provider.name, null) }.getOrNull()

    override fun has(provider: ProviderId): Boolean = get(provider) != null

    override fun clear(provider: ProviderId) {
        prefs.edit().remove(provider.name).apply()
    }

    companion object {
        private const val PREFS_FILE = "arxiver_ai_keys"
    }
}
