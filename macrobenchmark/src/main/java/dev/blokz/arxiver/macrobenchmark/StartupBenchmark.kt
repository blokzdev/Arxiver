package dev.blokz.arxiver.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start → Today (D1). Reports TTID (and TTFD once Today calls `reportFullyDrawn`). Corpus-independent — the seed
 * runs async, off the first-frame path. Both compilation modes run so the Baseline-Profile win can be sized; the
 * `Partial(Require)` variant asserts a profile is installed (generate it via `BaselineProfileGenerator` first).
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupNoCompilation() = startup(CompilationMode.None())

    @Test
    fun startupBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) =
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = mode,
            startupMode = StartupMode.COLD,
            iterations = BENCHMARK_ITERATIONS,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            device.wait(Until.hasObject(By.res(TODAY_SCREEN)), UI_TIMEOUT_MS)
        }
}
