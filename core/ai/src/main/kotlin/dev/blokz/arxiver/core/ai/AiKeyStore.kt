package dev.blokz.arxiver.core.ai

/**
 * Storage for BYOK provider keys (SPEC-AI-PROVIDERS §4). The production
 * implementation is [AiKeyVault] (encrypted, Keystore-backed); the interface
 * lets callers (the provider registry, settings ViewModel) depend on the
 * contract rather than the crypto, and tests substitute an in-memory fake.
 *
 * Red lines hold for every implementation: keys are write-only from the UI's
 * side — [get] feeds providers, never the UI — and never reach the DB, logs,
 * exports, or backups.
 */
interface AiKeyStore {
    fun put(
        provider: ProviderId,
        key: String,
    )

    fun get(provider: ProviderId): String?

    fun has(provider: ProviderId): Boolean

    fun clear(provider: ProviderId)
}
