package dev.blokz.arxiver

import dev.blokz.arxiver.core.model.ArxivId
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first-run routing fork (P-Prove PP.4). Pure JVM — no Activity/Compose/Hilt, so it's a reliable CI gate on the
 * decision the async splash-held startup now makes (the Activity's splash timing itself is device-verified in D1).
 */
class StartupRoutingTest {
    private val aDeepLink = ArxivId("2401.00001")

    @Test
    fun `fresh install with no deep link starts onboarding`() {
        assertTrue(shouldStartOnboarding(onboarded = false, deepLinkPaperId = null))
    }

    @Test
    fun `an onboarded user never starts onboarding`() {
        assertFalse(shouldStartOnboarding(onboarded = true, deepLinkPaperId = null))
    }

    @Test
    fun `a deep link bypasses onboarding even before the user has onboarded`() {
        assertFalse(shouldStartOnboarding(onboarded = false, deepLinkPaperId = aDeepLink))
    }

    @Test
    fun `an onboarded user with a deep link does not start onboarding`() {
        assertFalse(shouldStartOnboarding(onboarded = true, deepLinkPaperId = aDeepLink))
    }
}
