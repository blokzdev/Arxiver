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

/**
 * The next mode in the reader toolbar's cycle: SYSTEM → LIGHT → DARK → SYSTEM. Explicit `when` (not `entries`
 * modulo) so the wrap is a stated contract, not an accident of declaration order — reordering the enum can't
 * silently reshuffle the cycle. Lets the reader reach SYSTEM, which the old LIGHT⇄DARK flip couldn't (PP.1).
 */
fun ReaderThemeMode.next(): ReaderThemeMode =
    when (this) {
        ReaderThemeMode.SYSTEM -> ReaderThemeMode.LIGHT
        ReaderThemeMode.LIGHT -> ReaderThemeMode.DARK
        ReaderThemeMode.DARK -> ReaderThemeMode.SYSTEM
    }
