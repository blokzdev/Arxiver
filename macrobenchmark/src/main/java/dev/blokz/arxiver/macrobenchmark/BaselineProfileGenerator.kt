package dev.blokz.arxiver.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates `app/src/main/baseline-prof.txt` over the cold → Today → scroll journey (D4), on `nonMinifiedRelease`.
 * Idempotent seeding is load-bearing: `collect` runs multiple iterations — iteration 1 seeds, 2..N hit the sentinel
 * and short-circuit. Generation is device-only and opt-in (`-Pandroidx.baselineprofile.skipgeneration=false`); CI's
 * `./gradlew build` never runs it (`skipgeneration=true` + `automaticGenerationDuringBuild=false`).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() =
        rule.collect(packageName = TARGET_PACKAGE) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.textContains(SEEDED_ANCHOR)), SEED_TIMEOUT_MS)
            val feed = device.findObject(By.res(TODAY_SCREEN))
            feed.setGestureMargin(device.displayWidth / 5)
            repeat(2) { feed.fling(Direction.DOWN) }
        }
}
