package dev.blokz.arxiver.core.ai

import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trips the provider-keyed encrypted vault under Robolectric
 * (SPEC-AI-PROVIDERS §4). On-device Keystore behavior is tracked separately in
 * VERIFICATION.md; this asserts the put/get/has/clear contract.
 */
@RunWith(RobolectricTestRunner::class)
class AiKeyVaultTest {
    private val vault = AiKeyVault(ApplicationProvider.getApplicationContext())

    @AfterTest
    fun tearDown() {
        ProviderId.entries.forEach { vault.clear(it) }
    }

    @Test
    fun `put then get returns the key`() {
        vault.put(ProviderId.CLAUDE, "sk-claude")
        assertEquals("sk-claude", vault.get(ProviderId.CLAUDE))
        assertTrue(vault.has(ProviderId.CLAUDE))
    }

    @Test
    fun `keys are isolated per provider`() {
        vault.put(ProviderId.CLAUDE, "sk-claude")
        vault.put(ProviderId.GEMINI, "sk-gemini")
        assertEquals("sk-claude", vault.get(ProviderId.CLAUDE))
        assertEquals("sk-gemini", vault.get(ProviderId.GEMINI))
    }

    @Test
    fun `unset provider has no key`() {
        assertNull(vault.get(ProviderId.GEMINI))
        assertFalse(vault.has(ProviderId.GEMINI))
    }

    @Test
    fun `clear removes the key`() {
        vault.put(ProviderId.CLAUDE, "sk-claude")
        vault.clear(ProviderId.CLAUDE)
        assertNull(vault.get(ProviderId.CLAUDE))
        assertFalse(vault.has(ProviderId.CLAUDE))
    }

    @Test
    fun `the optional Semantic Scholar key round-trips in its own slot (PT3)`() {
        assertFalse(vault.has(ProviderId.SEMANTIC_SCHOLAR), "unset by default")
        vault.put(ProviderId.SEMANTIC_SCHOLAR, "s2-key")
        vault.put(ProviderId.CLAUDE, "sk-claude")
        assertEquals("s2-key", vault.get(ProviderId.SEMANTIC_SCHOLAR))
        assertTrue(vault.has(ProviderId.SEMANTIC_SCHOLAR))
        // Isolated from the chat providers — S2's slot is independent.
        assertEquals("sk-claude", vault.get(ProviderId.CLAUDE))
        vault.clear(ProviderId.SEMANTIC_SCHOLAR)
        assertNull(vault.get(ProviderId.SEMANTIC_SCHOLAR))
        assertEquals("sk-claude", vault.get(ProviderId.CLAUDE), "clearing S2 leaves other keys intact")
    }
}
