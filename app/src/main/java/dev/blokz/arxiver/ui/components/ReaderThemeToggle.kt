package dev.blokz.arxiver.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.blokz.arxiver.R
import dev.blokz.arxiver.data.ReaderThemeMode
import dev.blokz.arxiver.data.next

/**
 * The one reader-toolbar theme control, shared by BOTH readers (PDF + HTML) so they can never drift apart
 * (PP.1). A single tap advances the shared [ReaderThemeMode] one step through SYSTEM → LIGHT → DARK → SYSTEM,
 * making SYSTEM reachable from the reader — the old hand-rolled LIGHT⇄DARK flip could never land on it.
 *
 * The glyph shows the mode that is CURRENTLY active (SYSTEM=Contrast/auto, LIGHT=sun, DARK=moon), not the next
 * action — a three-state cycle has no single "target", so a current-state icon is the honest read (and it
 * matches the Contrast glyph the Settings reader section already uses for SYSTEM). The activate-to-switch hint
 * lives in the [contentDescription] so TalkBack announces both the current mode and what a tap will do.
 */
@Composable
fun ReaderThemeToggle(
    mode: ReaderThemeMode,
    onSetMode: (ReaderThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon =
        when (mode) {
            ReaderThemeMode.SYSTEM -> Icons.Filled.Contrast
            ReaderThemeMode.LIGHT -> Icons.Filled.LightMode
            ReaderThemeMode.DARK -> Icons.Filled.DarkMode
        }
    val description =
        when (mode) {
            ReaderThemeMode.SYSTEM -> stringResource(R.string.cd_reader_theme_system)
            ReaderThemeMode.LIGHT -> stringResource(R.string.cd_reader_theme_light)
            ReaderThemeMode.DARK -> stringResource(R.string.cd_reader_theme_dark)
        }
    IconButton(onClick = { onSetMode(mode.next()) }, modifier = modifier) {
        Icon(imageVector = icon, contentDescription = description)
    }
}
