package dev.blokz.arxiver.core.ai

/** Outcome of resolving which provider a chat turn should use. */
sealed interface ProviderResolution {
    data class Resolved(val provider: AiProvider) : ProviderResolution

    /** No provider is usable — UI should prompt the user to configure one. */
    data object NotConfigured : ProviderResolution
}

/**
 * Picks the [AiProvider] for a chat turn (SPEC-AI-PROVIDERS chat orchestration).
 *
 * Policy: respect the user's explicit selection by default. When the user opts
 * into [preferOnDevice] ("prefer on-device when ready"), an on-device engine wins
 * whenever it's ready — the privacy/cost choice. If the selection isn't usable,
 * fall back to any usable provider (on-device first).
 *
 * "Usable" means: a cloud provider has a key (`registry.isConfigured`), or — for
 * on-device — an engine is actually loaded/ready ([onDeviceReady]). On-device
 * readiness can't come from `registry.isConfigured` (a key-less provider always
 * reports configured), so it's an explicit seam. All inputs are suspend seams so
 * this stays free of `:app`/DataStore deps and pure-testable.
 */
class ProviderResolver(
    private val registry: ProviderRegistry,
    private val selected: suspend () -> ProviderId?,
    private val preferOnDevice: suspend () -> Boolean,
    private val onDeviceReady: suspend () -> Boolean,
) {
    suspend fun resolve(): ProviderResolution {
        val ready = onDeviceReady()

        if (preferOnDevice() && ready) {
            registry.provider(ProviderId.ON_DEVICE)?.let { return ProviderResolution.Resolved(it) }
        }

        val chosen = selected()
        if (chosen != null && usable(chosen, ready)) {
            registry.provider(chosen)?.let { return ProviderResolution.Resolved(it) }
        }

        // Selection absent/unusable: use any usable provider, on-device first.
        val fallback =
            registry.all()
                .sortedByDescending { it.id == ProviderId.ON_DEVICE }
                .firstOrNull { usable(it.id, ready) }
        return fallback?.let { ProviderResolution.Resolved(it) } ?: ProviderResolution.NotConfigured
    }

    private fun usable(
        id: ProviderId,
        onDeviceReady: Boolean,
    ): Boolean = if (id == ProviderId.ON_DEVICE) onDeviceReady else registry.isConfigured(id)
}
