package dev.blokz.arxiver.core.ai

/**
 * The set of AI providers the app knows about and which of them are usable
 * right now (SPEC-AI-PROVIDERS §2). "Configured" = a key is present in the
 * [AiKeyVault] (cloud BYOK). **Key-less providers (on-device) ALWAYS report
 * configured, regardless of whether any model is installed** — [isConfigured]
 * is never a readiness gate. On-device readiness is [OnDeviceProvider.isReady]
 * (any wired engine ready), consumed via `ProviderResolver`'s `onDeviceReady` seam.
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

    /** Cloud: a key is stored. Key-less (on-device): always true — NOT a readiness signal. */
    fun isConfigured(id: ProviderId): Boolean {
        val provider = provider(id) ?: return false
        return if (provider.capability.requiresKey) keyStore.has(id) else true
    }

    fun configured(): List<AiProvider> = providers.filter { isConfigured(it.id) }
}
