package dev.blokz.arxiver.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.feature.browse.BrowseScreen
import dev.blokz.arxiver.feature.browse.CategoryFeedScreen
import dev.blokz.arxiver.feature.library.FilteredPapersScreen
import dev.blokz.arxiver.feature.library.LibraryScreen
import dev.blokz.arxiver.feature.paper.ConnectionsScreen
import dev.blokz.arxiver.feature.paper.PaperDetailScreen
import dev.blokz.arxiver.feature.pdf.PdfViewerScreen
import dev.blokz.arxiver.feature.search.SearchScreen
import dev.blokz.arxiver.feature.today.TodayScreen
import dev.blokz.arxiver.ui.navigation.TopLevelDestination

object Routes {
    const val CATEGORY_FEED = "browse/category/{code}?title={title}"
    const val PAPER_DETAIL = "paper/{id}"
    const val PDF_VIEWER = "paper/{id}/pdf"
    const val CONNECTIONS = "paper/{id}/graph"
    const val FILTERED_PAPERS = "library/{mode}/{id}?title={title}"

    fun categoryFeed(
        code: String,
        title: String,
    ) = "browse/category/$code?title=${Uri.encode(title)}"

    /** Paper ids are URI-encoded: legacy ids like `math/0211159` contain a slash. */
    fun paperDetail(id: ArxivId) = "paper/${Uri.encode(id.value)}"

    fun pdfViewer(id: String) = "paper/${Uri.encode(id)}/pdf"

    fun connections(id: String) = "paper/${Uri.encode(id)}/graph"

    fun filteredPapers(
        mode: String,
        id: Long,
        title: String,
    ) = "library/$mode/$id?title=${Uri.encode(title)}"
}

@Composable
fun ArxiverApp(deepLinkPaperId: ArxivId? = null) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    LaunchedEffect(deepLinkPaperId) {
        deepLinkPaperId?.let { navController.navigate(Routes.paperDetail(it)) }
    }

    val showBottomBar = TopLevelDestination.entries.any { it.route == currentDestination?.route }

    Scaffold(
        bottomBar = { if (showBottomBar) ArxiverBottomBar(navController) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.Today.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopLevelDestination.Today.route) {
                TodayScreen(
                    onPaperClick = { id -> navController.navigate("paper/${Uri.encode(id)}") },
                    onGoBrowse = {
                        navController.navigate(TopLevelDestination.Browse.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(TopLevelDestination.Browse.route) {
                BrowseScreen(
                    onCategoryClick = { code, title ->
                        navController.navigate(Routes.categoryFeed(code, title))
                    },
                )
            }
            composable(TopLevelDestination.Search.route) {
                SearchScreen(
                    onPaperClick = { id -> navController.navigate("paper/${Uri.encode(id)}") },
                )
            }
            composable(TopLevelDestination.Library.route) {
                LibraryScreen(
                    onPaperClick = { id -> navController.navigate("paper/${Uri.encode(id)}") },
                    onCollectionClick = { id, name ->
                        navController.navigate(Routes.filteredPapers("collection", id, name))
                    },
                    onTagClick = { id, name ->
                        navController.navigate(Routes.filteredPapers("tag", id, "#$name"))
                    },
                )
            }

            composable(Routes.FILTERED_PAPERS) {
                FilteredPapersScreen(
                    onBack = { navController.popBackStack() },
                    onPaperClick = { id -> navController.navigate("paper/${Uri.encode(id)}") },
                )
            }

            composable(Routes.CATEGORY_FEED) {
                CategoryFeedScreen(
                    onBack = { navController.popBackStack() },
                    onPaperClick = { id -> navController.navigate("paper/${Uri.encode(id)}") },
                )
            }
            composable(Routes.PAPER_DETAIL) {
                PaperDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPdf = { id -> navController.navigate(Routes.pdfViewer(id)) },
                    onPaperClick = { id -> navController.navigate("paper/${Uri.encode(id)}") },
                    onOpenConnections = { id -> navController.navigate(Routes.connections(id)) },
                )
            }
            composable(Routes.PDF_VIEWER) {
                PdfViewerScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.CONNECTIONS) {
                ConnectionsScreen(
                    onBack = { navController.popBackStack() },
                    onPaperClick = { id -> navController.navigate("paper/${Uri.encode(id)}") },
                )
            }
        }
    }
}

@Composable
private fun ArxiverBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    NavigationBar {
        TopLevelDestination.entries.forEach { destination ->
            val selected =
                currentDestination?.hierarchy
                    ?.any { it.route == destination.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Icon(
                        imageVector = if (selected) destination.selectedIcon else destination.icon,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(destination.labelRes)) },
            )
        }
    }
}
