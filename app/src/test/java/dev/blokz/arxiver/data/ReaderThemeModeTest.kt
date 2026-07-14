package dev.blokz.arxiver.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RNM — the pure effective-dark resolver both readers share. */
class ReaderThemeModeTest {
    @Test
    fun `resolveReaderDark truth table`() {
        // LIGHT / DARK are absolute; SYSTEM follows the OS either way.
        assertFalse(resolveReaderDark(ReaderThemeMode.LIGHT, systemDark = true))
        assertFalse(resolveReaderDark(ReaderThemeMode.LIGHT, systemDark = false))
        assertTrue(resolveReaderDark(ReaderThemeMode.DARK, systemDark = false))
        assertTrue(resolveReaderDark(ReaderThemeMode.DARK, systemDark = true))
        assertTrue(resolveReaderDark(ReaderThemeMode.SYSTEM, systemDark = true))
        assertFalse(resolveReaderDark(ReaderThemeMode.SYSTEM, systemDark = false))
    }

    @Test
    fun `SYSTEM is the default so a fresh install keeps the OS-following behaviour`() {
        assertEquals(ReaderThemeMode.SYSTEM, ReaderThemeMode.entries.first())
    }
}
