package dev.blokz.arxiver.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.blokz.arxiver.R
import dev.blokz.arxiver.ui.components.PlaceholderScreen

// Real library arrives in Phase 2 (ROADMAP 2.2).
@Composable
fun LibraryScreen() {
    PlaceholderScreen(
        title = stringResource(R.string.nav_library),
        subtitle = stringResource(R.string.placeholder_library),
    )
}
