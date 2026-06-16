package dev.blokz.arxiver.ui.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwipeablePaperRowTest {
    @Test
    fun `both directions enabled exposes two TalkBack actions`() {
        val actions = swipeAccessibilityActions("Save", "Dismiss", onSwipeSave = {}, onSwipeDismiss = {})
        assertEquals(listOf("Save", "Dismiss"), actions.map { it.label })
    }

    @Test
    fun `save-only exposes just the save action`() {
        val actions = swipeAccessibilityActions("Save", "Dismiss", onSwipeSave = {}, onSwipeDismiss = null)
        assertEquals(listOf("Save"), actions.map { it.label })
    }

    @Test
    fun `no swipe handlers exposes no actions`() {
        val actions = swipeAccessibilityActions("Save", "Dismiss", onSwipeSave = null, onSwipeDismiss = null)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `invoking an action runs its handler`() {
        var saved = false
        val actions =
            swipeAccessibilityActions("Save", "Dismiss", onSwipeSave = { saved = true }, onSwipeDismiss = null)
        assertTrue(actions.single().action?.invoke() == true)
        assertTrue(saved)
    }
}
