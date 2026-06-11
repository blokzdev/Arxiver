package dev.blokz.arxiver.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.blokz.arxiver.R
import dev.blokz.arxiver.ui.components.PlaceholderScreen

@Composable
fun SearchScreen() {
    PlaceholderScreen(
        title = stringResource(R.string.nav_search),
        subtitle = stringResource(R.string.placeholder_search),
    )
}
