package dev.blokz.arxiver.core.ai

/**
 * The set of AI providers the app knows about and which of them are usable
 * right now (SPEC-AI-PROVIDERS §2). "Configured" = a key is present in the
 * [AiKeyVault] (cloud BYOK); on-device providers (P1.2) will report configured
 * once their model is installed.
 *
 * The *selected default* is persisted in the app's settings (DataStore), not
 * here — this module stays free of `:app` deps. Adding a provider is additive:
 * register its [AiProvider] in DI and it appears in [all].
 */
class ProviderRegistry(
    private val providers: List<AiProvider>,
    private val keyStore: AiKeyStore,
) {
    fun all(): List<AiProvider> = providers

    fun provider(id: ProviderId): AiProvider? = providers.firstOrNull { it.id == id }

    /** True when the provider can be used (cloud: a key is stored). */
    fun isConfigured(id: ProviderId): Boolean {
        val provider = provider(id) ?: return false
        return if (provider.capability.requiresKey) keyStore.has(id) else true
    }

    fun configured(): List<AiProvider> = providers.filter { isConfigured(it.id) }
}
