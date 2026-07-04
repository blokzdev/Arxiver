package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.common.AppError
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The low-disk guard for the native engine's first-init XNNPack cache write (PA readiness hotfix,
 * 2026-07-03). Without it a full disk SIGABRTs the whole app inside `nativeCreateEngine`
 * (reproduced live on the verification emulator at 88% disk) — uncatchable from Kotlin.
 */
class EngineCachePreflightTest {
    @Test
    fun `first init on a too-full disk is refused`() {
        assertTrue(
            EngineCachePreflight.wouldExhaustDisk(modelBytes = 614L, usableBytes = 613L, hasExistingCache = false),
        )
    }

    @Test
    fun `first init with enough headroom proceeds`() {
        assertFalse(
            EngineCachePreflight.wouldExhaustDisk(modelBytes = 614L, usableBytes = 614L, hasExistingCache = false),
        )
    }

    @Test
    fun `re-init with an existing cache never needs headroom`() {
        assertFalse(EngineCachePreflight.wouldExhaustDisk(modelBytes = 614L, usableBytes = 0L, hasExistingCache = true))
    }

    @Test
    fun `check passes when a cache file for this model already exists, regardless of free space`() {
        // The on-device cache is named "<modelFileName>.xnnpack_cache_<...>" — prefix-matched.
        val cacheDir = Files.createTempDirectory("preflight").toFile()
        val modelFile = Files.createTempDirectory("model").toFile().resolve("m.litertlm")
        modelFile.writeBytes(ByteArray(16))
        cacheDir.resolve("m.litertlm.xnnpack_cache_123_456").writeBytes(ByteArray(1))

        EngineCachePreflight.check(modelFile, cacheDir) // must not throw
    }

    @Test
    fun `check refuses first init on a full disk with a typed Storage error, never a crash`() {
        val cacheDir = Files.createTempDirectory("preflight").toFile()
        val modelFile = Files.createTempDirectory("model").toFile().resolve("m.litertlm")
        modelFile.writeBytes(ByteArray(16))

        val e =
            assertFailsWith<AiException> {
                EngineCachePreflight.check(modelFile, cacheDir, usableSpace = { 0L })
            }
        assertTrue(e.error is AppError.Storage)
    }
}
