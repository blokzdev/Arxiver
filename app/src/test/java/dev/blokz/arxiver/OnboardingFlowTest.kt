package dev.blokz.arxiver

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dev.blokz.arxiver.core.database.TaxonomySeeder
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * Headless diagnostic for the first-run flow: onboarding → follow a
 * category → "Start reading" → Today. Used to root-cause the v1.1.0 device
 * crash (proved the logic path sound; the crash was R8 stripping
 * TodayViewModel's HiltModules\$BindsModule — see proguard-rules.pro).
 *
 * Run standalone: `./gradlew :app:testDebugUnitTest --tests "*.OnboardingFlowTest"`.
 * Ignored in the suite: order-flaky when sharing a Robolectric sandbox with
 * other DB/DataStore tests (singleton state across same-config classes).
 */
@Ignore("manual diagnostic — run standalone; order-flaky in the full suite")
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class OnboardingFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var taxonomySeeder: TaxonomySeeder

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Before
    fun setUp() {
        // Debug variant only: the release-variant unit-test run has flaky
        // compose-idle timing here, and the R8 effects this guards against
        // are only observable on a real release APK anyway.
        assumeTrue(BuildConfig.DEBUG)
        hiltRule.inject()
        // HiltTestApplication isn't our Application, so WorkManager's
        // on-demand init has no Configuration.Provider — initialize manually.
        val context = ApplicationProvider.getApplicationContext<Context>()
        runCatching {
            WorkManager.initialize(
                context,
                Configuration.Builder().setWorkerFactory(workerFactory).build(),
            )
        }
        runBlocking { taxonomySeeder.seed() }
    }

    @Test
    fun `follow a category and press start reading without crashing`() {
        composeRule.onNodeWithText("Start reading").assertExists()

        composeRule.onNodeWithText("Quantum Physics").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Start reading").performClick()
        composeRule.waitForIdle()

        // Landed on Today (top bar title) — the press path survived.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Today").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Start reading").assertCountEquals(0)
    }
}
