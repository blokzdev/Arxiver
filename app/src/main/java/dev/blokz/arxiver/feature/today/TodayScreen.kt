package dev.blokz.arxiver.feature.today

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.blokz.arxiver.R
import dev.blokz.arxiver.ui.components.PlaceholderScreen

@Composable
fun TodayScreen() {
    PlaceholderScreen(
        title = stringResource(R.string.nav_today),
        subtitle = stringResource(R.string.placeholder_today),
    )
}
