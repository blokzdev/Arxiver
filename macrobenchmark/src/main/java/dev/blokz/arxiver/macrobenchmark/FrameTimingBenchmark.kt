package dev.blokz.arxiver.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Today-feed scroll jank (D3). The setup gates on SEEDED content, not just `today_screen` — the screen renders before
 * the async seed populates the list, so waiting on the tag alone would measure `EmptyState` (Finding #2). The tag is
 * on the Today `Scaffold`; if fling doesn't register on the device, move it to the `LazyColumn` (a PP.5 device tweak).
 */
@RunWith(AndroidJUnit4::class)
class FrameTimingBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun scrollTodayFeed() =
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = BENCHMARK_ITERATIONS,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                device.wait(Until.hasObject(By.textContains(SEEDED_ANCHOR)), SEED_TIMEOUT_MS)
            },
        ) {
            val feed = device.findObject(By.res(TODAY_SCREEN))
            feed.setGestureMargin(device.displayWidth / 5)
            repeat(3) { feed.fling(Direction.DOWN) }
        }
}
