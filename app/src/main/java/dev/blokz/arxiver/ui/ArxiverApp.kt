package dev.blokz.arxiver.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.feature.browse.BrowseScreen
import dev.blokz.arxiver.feature.browse.CategoryFeedScreen
import dev.blokz.arxiver.feature.chat.ChatHistoryScreen
import dev.blokz.arxiver.feature.claude.DispatchHistoryScreen
import dev.blokz.arxiver.feature.claude.RoutineSetupScreen
import dev.blokz.arxiver.feature.claude.RoutinesScreen
import dev.blokz.arxiver.feature.claude.TemplateCatalogScreen
import dev.blokz.arxiver.feature.claude.TemplateDetailScreen
import dev.blokz.arxiver.feature.knowledgemap.KnowledgeMapScreen
import dev.blokz.arxiver.feature.library.FilteredPapersScreen
import dev.blokz.arxiver.feature.library.LibraryScreen
import dev.blokz.arxiver.feature.onboarding.OnboardingScreen
import dev.blokz.arxiver.feature.paper.ConnectionsScreen
import dev.blokz.arxiver.feature.paper.PaperDetailScreen
import dev.blokz.arxiver.feature.pdf.PdfViewerScreen
import dev.blokz.arxiver.feature.search.SearchScreen
import dev.blokz.arxiver.feature.settings.AiProviderSettingsScreen
import dev.blokz.arxiver.feature.settings.SettingsScreen
import dev.blokz.arxiver.feature.today.TodayScreen
import dev.blokz.arxiver.ui.feedback.FeedbackHost
import dev.blokz.arxiver.ui.feedback.FeedbackViewModel
import dev.blokz.arxiver.ui.feedback.LocalFeedbackController
import dev.blokz.arxiver.ui.navigation.TopLevelDestination
import dev.blokz.arxiver.ui.theme.ArxiverMotion

/** Bottom-tab switches have no direction — fade through, never slide. */
private val fadeThroughEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
    { fadeIn(tween(ArxiverMotion.DURATION_MEDIUM)) }
private val fadeThroughExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
    { fadeOut(tween(ArxiverMotion.DURATION_SHORT)) }

object Routes {
    const val CATEGORY_FEED = "browse/category/{code}?title={title}"
    const val PAPER_DETAIL = "paper/{id}"
    const val PDF_VIEWER = "paper/{id}/pdf"
    const val CONNECTIONS = "paper/{id}/graph"
    const val ROUTINES = "claude/routines"
    const val TEMPLATE_CATALOG = "claude/templates"
    const val TEMPLATE_DETAIL = "claude/templates/{templateId}"
    const val ROUTINE_SETUP = "claude/setup?templateId={templateId}"
    const val SETTINGS = "settings"
    const val AI_SETTINGS = "settings/ai"
    const val ONBOARDING = "onboarding"
    const val DISPATCH_HISTORY = "claude/history"
    const val CHAT_HISTORY = "chat/history"
    const val FILTERED_PAPERS = "library/{mode}/{id}?title={title}"
    const val KNOWLEDGE_MAP = "map/{scope}/{id}"

    fun categoryFeed(
        code: String,
        title: String,
    ) = "browse/category/$code?title=${Uri.encode(title)}"

    /** Paper ids are URI-encoded: legacy ids like `math/0211159` contain a slash. */
    fun paperDetail(id: ArxivId) = "paper/${Uri.encode(id.value)}"

    fun pdfViewer(id: String) = "paper/${Uri.encode(id)}/pdf"

    fun connections(id: String) = "paper/${Uri.encode(id)}/graph"

    /** Full-screen knowledge map; [scope] is `collection` (id = collection id) or `paper` (id = arXiv id). */
    fun knowledgeMap(
        scope: String,
        id: String,
    ) = "map/$scope/${Uri.encode(id)}"

    fun filteredPapers(
        mode: String,
        id: Long,
        title: String,
    ) = "library/$mode/$id?title=${Uri.encode(title)}"

    fun templateDetail(templateId: String) = "claude/templates/$templateId"

    fun routineSetup(templateId: String?) = templateId?.let { "claude/setup?templateId=$it" } ?: "claude/setup"
}

@Composable
fun ArxiverApp(
    deepLinkPaperId: ArxivId? = null,
    startOnboarding: Boolean = false,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    LaunchedEffect(deepLinkPaperId) {
        deepLinkPaperId?.let { navController.navigate(Routes.paperDetail(it)) }
    }

    val showBottomBar = TopLevelDestination.entries.any { it.route == currentDestination?.route }

    val feedbackController = hiltViewModel<FeedbackViewModel>().controller

    CompositionLocalProvider(LocalFeedbackController provides feedbackController) {
        Scaffold(
            bottomBar = { if (showBottomBar) ArxiverBottomBar(navController) },
            snackbarHost = { FeedbackHost(feedbackController) },
        ) { innerPadding ->
            // Motion grammar (SPEC-UI §1): stacked pushes slide in gently, pops
            // mirror them; bottom-tab switches fade through (no direction).
            NavHost(
                navController = navController,
                startDestination = if (startOnboarding) Routes.ONBOARDING else TopLevelDestination.Today.route,
                // Consume the insets the outer Scaffold already applied so each screen's own
                // Scaffold doesn't re-pad for the status/navigation bars (the double-applied
                // bottom nav inset showed as a blank strip above the bottom bar).
                modifier =
                    Modifier
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding),
                enterTransition = {
                    fadeIn(tween(ArxiverMotion.DURATION_LONG, easing = ArxiverMotion.DecelerateEasing)) +
                        slideInHorizontally(
                            tween(ArxiverMotion.DURATION_LONG, easing = ArxiverMotion.DecelerateEasing),
                        ) { it / 10 }
                },
                exitTransition = { fadeOut(tween(ArxiverMotion.DURATION_SHORT)) },
                popEnterTransition = { fadeIn(tween(ArxiverMotion.DURATION_LONG)) },
                popExitTransition = {
                    fadeOut(tween(ArxiverMotion.DURATION_SHORT)) +
                        slideOutHorizontally(
                            tween(ArxiverMotion.DURATION_LONG, easing = ArxiverMotion.AccelerateEasing),
                        ) { it / 10 }
                },
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(
                        onDone = {
                            navController.navigate(TopLevelDestination.Today.route) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenRoutines = { navController.navigate(Routes.ROUTINES) },
                        onOpenHistory = { navController.navigate(Routes.DISPATCH_HISTORY) },
                        onOpenTemplates = { navController.navigate(Routes.TEMPLATE_CATALOG) },
                        onOpenAiProviders = { navController.navigate(Routes.AI_SETTINGS) },
                        onOpenChatHistory = { navController.navigate(Routes.CHAT_HISTORY) },
                    )
                }
                composable(Routes.AI_SETTINGS) {
                    AiProviderSettingsScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.CHAT_HISTORY) {
                    ChatHistoryScreen(
                        onBack = { navController.popBackStack() },
                        onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) },
                    )
                }
                composable(
                    route = TopLevelDestination.Today.route,
                    enterTransition = fadeThroughEnter,
                    exitTransition = fadeThroughExit,
                    popEnterTransition = fadeThroughEnter,
                    popExitTransition = fadeThroughExit,
                ) {
                    TodayScreen(
                        onPaperClick = { id -> navController.navigate("paper/${Uri.encode(id)}") },
                        onOpenRoutines = { navController.navigate(Routes.ROUTINES) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onGoBrowse = {
                            navController.navigate(TopLevelDestination.Browse.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable(
                    route = TopLevelDestination.Browse.route,
                    enterTransition = fadeThroughEnter,
                    exitTransition = fadeThroughExit,
                    popEnterTransition = fadeThroughEnter,
                    popExitTransition = fadeThroughExit,
                ) {
                    BrowseScreen(
                        onCategoryClick = { code, title ->
                            navController.navigate(Routes.categoryFeed(code, title))
                        },
                    )
                }
                composable(
                    route = TopLevelDestination.Search.route,
                    enterTransition = fadeThroughEnter,
                    exitTransition = fadeThroughExit,
                    popEnterTransition = fadeThroughEnter,
                    popExitTransition = fadeThroughExit,
                ) {
                    SearchScreen(
                        onPaperClick = { id -> navController.navigate("paper/${Uri.encode(id)}") },
                        onOpenRoutines = { navController.navigate(Routes.ROUTINES) },
                    )
                }
                composable(
                    route = TopLevelDestination.Library.route,
                    enterTransition = fadeThroughEnter,
                    exitTransition = fadeThroughExit,
                    popEnterTransition = fadeThroughEnter,
                    popExitTransition = fadeThroughExit,
                ) {
                    LibraryScreen(
                        onPaperClick = { id -> navController.navigate("paper/${Uri.encode(id)}") },
                        onOpenRoutines = { navController.navigate(Routes.ROUTINES) },
                        onOpenHistory = { navController.navigate(Routes.DISPATCH_HISTORY) },
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
                        onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) },
                        onOpenRoutines = { navController.navigate(Routes.ROUTINES) },
                        onOpenKnowledgeMap = { cid ->
                            navController.navigate(Routes.knowledgeMap("collection", cid.toString()))
                        },
                    )
                }

                composable(Routes.CATEGORY_FEED) {
                    CategoryFeedScreen(
                        onBack = { navController.popBackStack() },
                        onPaperClick = { id -> navController.navigate("paper/${Uri.encode(id)}") },
                        onOpenRoutines = { navController.navigate(Routes.ROUTINES) },
                    )
                }
                composable(Routes.PAPER_DETAIL) {
                    PaperDetailScreen(
                        onBack = { navController.popBackStack() },
                        onOpenPdf = { id -> navController.navigate(Routes.pdfViewer(id)) },
                        onPaperClick = { id -> navController.navigate("paper/${Uri.encode(id)}") },
                        onOpenConnections = { id -> navController.navigate(Routes.connections(id)) },
                        onOpenRoutines = { navController.navigate(Routes.ROUTINES) },
                        onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) },
                    )
                }
                composable(Routes.PDF_VIEWER) {
                    PdfViewerScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.ROUTINES) {
                    RoutinesScreen(
                        onBack = { navController.popBackStack() },
                        onOpenTemplates = { navController.navigate(Routes.TEMPLATE_CATALOG) },
                    )
                }
                composable(Routes.TEMPLATE_CATALOG) {
                    TemplateCatalogScreen(
                        onBack = { navController.popBackStack() },
                        onTemplateClick = { id -> navController.navigate(Routes.templateDetail(id)) },
                    )
                }
                composable(Routes.TEMPLATE_DETAIL) { entry ->
                    TemplateDetailScreen(
                        templateId = entry.arguments?.getString("templateId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onStartSetup = { id -> navController.navigate(Routes.routineSetup(id)) },
                    )
                }
                composable(
                    route = Routes.ROUTINE_SETUP,
                    arguments =
                        listOf(
                            navArgument("templateId") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                ) {
                    RoutineSetupScreen(
                        onExit = { navController.popBackStack() },
                        onDone = {
                            // Land on the routines list, where the new routine now shows.
                            if (!navController.popBackStack(Routes.ROUTINES, inclusive = false)) {
                                navController.navigate(Routes.ROUTINES) {
                                    popUpTo(Routes.TEMPLATE_CATALOG) { inclusive = true }
                                }
                            }
                        },
                    )
                }
                composable(Routes.DISPATCH_HISTORY) {
                    DispatchHistoryScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.CONNECTIONS) {
                    ConnectionsScreen(
                        onBack = { navController.popBackStack() },
                        onPaperClick = { id -> navController.navigate("paper/${Uri.encode(id)}") },
                        onOpenMap = { id -> navController.navigate(Routes.knowledgeMap("paper", id)) },
                    )
                }
                composable(Routes.KNOWLEDGE_MAP) {
                    KnowledgeMapScreen(
                        onBack = { navController.popBackStack() },
                        onPaperClick = { id -> navController.navigate("paper/${Uri.encode(id)}") },
                    )
                }
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
