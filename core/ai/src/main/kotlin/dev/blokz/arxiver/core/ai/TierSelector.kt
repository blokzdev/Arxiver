package dev.blokz.arxiver.core.ai

/**
 * Chooses the best inference tier the device can use right now and the
 * graceful-degradation order (SPEC-AI-PROVIDERS §3): **Gemma → Nano → cloud →
 * none**. Gemma (when downloaded) is preferred over system Nano because Nano's
 * Prompt API caps output (~256 tokens, EN/KO) — too tight for paper summaries;
 * Nano stays the zero-download option and the recommendation when Gemma isn't
 * installed. Pure and deterministic — a tier appears only when actually usable,
 * so the recommendation never points at a back-end that would fail.
 */
object TierSelector {
    /** The single best usable tier (the head of [fallbackOrder]). */
    fun recommend(capability: DeviceCapability): InferenceTier = fallbackOrder(capability).first()

    /** Usable tiers, best-first, always ending in [InferenceTier.NONE]. */
    fun fallbackOrder(capability: DeviceCapability): List<InferenceTier> =
        buildList {
            if (capability.gemmaReady) add(InferenceTier.GEMMA)
            if (capability.nanoStatus == NanoStatus.AVAILABLE) add(InferenceTier.NANO)
            if (capability.cloudConfigured) add(InferenceTier.CLOUD)
            add(InferenceTier.NONE)
        }
}
