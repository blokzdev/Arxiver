package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.common.AppError
import java.io.File

/**
 * Preflight for the LiteRT-LM engine's first initialization (P-Atlas readiness hotfix, 2026-07-03).
 *
 * `Engine.initialize()` compiles and writes a per-model **XNNPack weight cache** into `cacheDir`
 * that is on the order of the model file itself (observed on-device: 788 MB for the 2.59 GB Gemma,
 * ~450 MB+ for the 614 MB Qwen). When the disk can't fit it, the native layer dies with an
 * **uncatchable SIGABRT** inside `nativeCreateEngine` — the whole app crashes instead of surfacing
 * an error (reproduced live on the verification emulator at 88% disk). This guard fails the turn
 * *gracefully* (`AiException(AppError.Storage)`) before the native call.
 *
 * Skipped when a cache for this model already exists (re-init needs no headroom). The bound is
 * deliberately simple — require free space ≥ the model size — generous for Gemma (~30% observed)
 * and about right for Qwen (~70%+ observed); a false trip just asks the user to free space.
 */
internal object EngineCachePreflight {
    /** Throws [AiException] ([AppError.Storage]) when the first-init cache write would run out of disk. */
    fun check(
        modelFile: File,
        cacheDir: File,
        // Injectable so the refusal path is testable (a JVM test can't fake a full real disk).
        usableSpace: (File) -> Long = { it.usableSpace },
    ) {
        val hasExistingCache = cacheDir.listFiles()?.any { it.name.startsWith(modelFile.name) } == true
        if (wouldExhaustDisk(modelFile.length(), usableSpace(cacheDir), hasExistingCache)) {
            throw AiException(
                AppError.Storage(
                    "Not enough free storage to prepare the on-device model " +
                        "(needs ~${modelFile.length() / (1024 * 1024)} MB free).",
                ),
            )
        }
    }

    /** The pure decision — split out so the failure branch is unit-testable without faking a full disk. */
    fun wouldExhaustDisk(
        modelBytes: Long,
        usableBytes: Long,
        hasExistingCache: Boolean,
    ): Boolean = !hasExistingCache && usableBytes < modelBytes
}
