package dev.blokz.arxiver.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Structural guard on the resolver's readiness seam (PA.3 Qwen-only bug, 2026-07-03; pattern:
 * `NoDirectNewCallStructuralTest`). The bug class: `AppModule`'s `onDeviceReady` hand-enumerated
 * engines (`gemma.isReady() || nano.isReady()`), so the list at `onDeviceProvider(...)` grew when
 * the Qwen light tier landed but the seam didn't — a Qwen-only device resolved `NotConfigured`.
 * `OnDeviceProvider.isReady()` is the single readiness source; this test makes a revert to
 * hand-enumeration a red build.
 */
class ResolverReadinessSourceStructuralTest {
    @Test
    fun `providerResolver delegates readiness to OnDeviceProvider isReady, never hand-enumerated engines`() {
        // :app sources live under src/main/java.
        val appModule = File("src/main/java/dev/blokz/arxiver/di/AppModule.kt")
        assertTrue(appModule.isFile, "must run from the :app module dir; cwd=${File("").absolutePath}")

        val text = appModule.readText()
        val start = text.indexOf("fun providerResolver")
        assertTrue(start >= 0, "AppModule must declare providerResolver")
        val end = text.indexOf("@Provides", startIndex = start).let { if (it == -1) text.length else it }
        val block = text.substring(start, end)

        assertTrue(
            Regex("""onDeviceProvider\s*(\.|::)\s*isReady""").containsMatchIn(block),
            "onDeviceReady must delegate to OnDeviceProvider.isReady() — the single on-device " +
                "readiness source (PA.3: a hand-enumerated seam left Qwen-only devices NotConfigured)",
        )
        assertTrue(
            !Regex("""\bEngine\b""").containsMatchIn(block),
            "providerResolver must not name engines — readiness derives from OnDeviceProvider's " +
                "wired engine list, so a future engine can never be forgotten here again",
        )
    }
}
