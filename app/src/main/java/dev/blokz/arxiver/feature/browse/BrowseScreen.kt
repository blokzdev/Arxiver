package dev.blokz.arxiver.feature.browse

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.blokz.arxiver.R
import dev.blokz.arxiver.ui.components.PlaceholderScreen

@Composable
fun BrowseScreen() {
    PlaceholderScreen(
        title = stringResource(R.string.nav_browse),
        subtitle = stringResource(R.string.placeholder_browse),
    )
}
