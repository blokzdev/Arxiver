package dev.blokz.arxiver.data

/**
 * The persisted reader night-mode preference (Phase P-Reader2, RNM) — one setting honoured by BOTH readers
 * (HTML + PDF). Tri-state, NOT a boolean: `SYSTEM` preserves the HTML reader's existing auto-follow-the-OS
 * behaviour (a boolean would silently force light/dark and regress it). Persisted as its `name` in DataStore.
 */
enum class ReaderThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The effective dark state for a reader, given the persisted [mode] and the current system dark state — the one
 * pure helper both readers share. `systemDark` is resolved live in the Compose layer (`isSystemInDarkTheme()`)
 * so a mid-read OS theme change repaints, rather than being seeded once from a stale configuration read.
 */
fun resolveReaderDark(
    mode: ReaderThemeMode,
    systemDark: Boolean,
): Boolean =
    when (mode) {
        ReaderThemeMode.SYSTEM -> systemDark
        ReaderThemeMode.LIGHT -> false
        ReaderThemeMode.DARK -> true
    }
