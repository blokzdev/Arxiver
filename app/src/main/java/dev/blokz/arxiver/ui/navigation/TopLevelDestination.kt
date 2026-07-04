package dev.blokz.arxiver.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Today
import androidx.compose.ui.graphics.vector.ImageVector
import dev.blokz.arxiver.R

enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Today(
        route = "today",
        labelRes = R.string.nav_today,
        icon = Icons.Outlined.Today,
        selectedIcon = Icons.Filled.Today,
    ),
    Explore(
        route = "explore",
        labelRes = R.string.nav_explore,
        icon = Icons.Outlined.Explore,
        selectedIcon = Icons.Filled.Explore,
    ),
    Library(
        route = "library",
        labelRes = R.string.nav_library,
        icon = Icons.AutoMirrored.Outlined.LibraryBooks,
        selectedIcon = Icons.AutoMirrored.Filled.LibraryBooks,
    ),
}
