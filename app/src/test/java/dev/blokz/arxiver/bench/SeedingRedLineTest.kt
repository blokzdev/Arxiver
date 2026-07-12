package dev.blokz.arxiver.bench

import dev.blokz.arxiver.BuildConfig
import org.junit.Test
import kotlin.test.assertFalse

/**
 * Red-line guard (P-Prove PP.3): the benchmark corpus seeder must NEVER run on a shipping build. CI runs `:app`
 * unit tests under BOTH the debug and release variants (see the `setForkEvery` note in build.gradle.kts), so this
 * single assertion proves `ENABLE_TEST_CORPUS` is compile-time `false` in both — the seeding hook in
 * `ArxiverApplication` is therefore dead code off the benchmark variants. A regression that flipped release (or
 * debug) ON — e.g. widening the `androidComponents` override to include `"release"` — fails this test in CI.
 * Only the ephemeral, non-debuggable `benchmarkRelease`/`nonMinifiedRelease` variants (never installed by users)
 * carry the flag `true`, and they have no unit-test source set of their own.
 */
class SeedingRedLineTest {
    @Test
    fun `test corpus seeding is disabled in debug and release`() {
        assertFalse(BuildConfig.ENABLE_TEST_CORPUS, "ENABLE_TEST_CORPUS must be false on any user-installable build")
    }
}
