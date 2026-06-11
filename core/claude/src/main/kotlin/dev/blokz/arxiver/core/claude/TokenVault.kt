package dev.blokz.arxiver.core.claude

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Keystore-backed storage for routine trigger tokens (SPEC-CLAUDE-BRIDGE §2).
 * The database stores only the alias this vault hands out. Tokens are
 * write-only from the UI's perspective and are excluded from every backup
 * path (CLAUDE.md red lines).
 */
class TokenVault(context: Context) {
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

    /** Stores [token] and returns the alias to persist in the database. */
    fun store(token: String): String {
        val alias = "routine_" + UUID.randomUUID().toString()
        prefs.edit().putString(alias, token).apply()
        return alias
    }

    /** Replaces the token behind an existing alias (re-authentication). */
    fun replace(
        alias: String,
        token: String,
    ) {
        prefs.edit().putString(alias, token).apply()
    }

    /** Null when missing or undecryptable (e.g. restored to a new device). */
    fun retrieve(alias: String): String? = runCatching { prefs.getString(alias, null) }.getOrNull()

    fun delete(alias: String) {
        prefs.edit().remove(alias).apply()
    }

    companion object {
        private const val PREFS_FILE = "arxiver_routine_tokens"
    }
}
