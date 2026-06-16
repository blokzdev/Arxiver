package dev.blokz.arxiver.ui.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectionStateTest {
    @Test
    fun `starts empty and inactive`() {
        val s = SelectionState()
        assertFalse(s.isActive)
        assertEquals(0, s.count)
        assertFalse(s.contains("a"))
    }

    @Test
    fun `toggle adds then removes the same id`() {
        val s = SelectionState()
        s.toggle("a")
        assertTrue(s.isActive)
        assertTrue(s.contains("a"))
        assertEquals(1, s.count)

        s.toggle("a")
        assertFalse(s.isActive)
        assertFalse(s.contains("a"))
        assertEquals(0, s.count)
    }

    @Test
    fun `tracks multiple ids and clears all`() {
        val s = SelectionState()
        s.toggle("a")
        s.toggle("b")
        s.toggle("c")
        assertEquals(3, s.count)

        s.clear()
        assertEquals(0, s.count)
        assertFalse(s.isActive)
    }

    @Test
    fun `restores from a saved set (rotation survival)`() {
        val restored = SelectionState(setOf("x", "y"))
        assertEquals(2, restored.count)
        assertTrue(restored.contains("x"))
        assertTrue(restored.contains("y"))
    }
}
