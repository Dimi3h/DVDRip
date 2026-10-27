package be.discrip.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import be.discrip.AppContainer
import be.discrip.R
import be.discrip.ui.components.HigTabBar
import be.discrip.ui.components.TabItem
import be.discrip.ui.home.HomeScreen
import be.discrip.ui.icons.HigIcons
import be.discrip.ui.metadata.MetadataScreen
import be.discrip.ui.settings.MetadataSettingsScreen
import be.discrip.ui.settings.NasSettingsScreen
import be.discrip.ui.settings.PlexSettingsScreen
import be.discrip.ui.settings.QualitySettingsScreen
import be.discrip.ui.settings.SettingsScreen
import be.discrip.ui.settings.StorageSettingsScreen
import be.discrip.ui.theme.HigMetrics
import be.discrip.ui.theme.HigTheme

private val pushEasing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f)
private const val PUSH_MILLIS = 380

private fun NavDestination?.isTabRoot(): Boolean =
    this != null && (hasRoute(HomeRoute::class) || hasRoute(SettingsRoute::class))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
    initialState.destination.isTabRoot() && targetState.destination.isTabRoot()

/** Pops [entry] only while it is the settled top screen, so a double tap cannot pop twice. */
private fun NavHostController.popIfResumed(entry: NavBackStackEntry) {
    if (entry.lifecycle.currentState == Lifecycle.State.RESUMED) popBackStack()
}

/** App root: theme, navigation with iOS push transitions, and the tab bar on the tab roots. */
@Composable
fun DiscRipRoot(container: AppContainer) {
    HigTheme {
        val navController = rememberNavController()

        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier.fillMaxSize().background(HigTheme.colors.groupedBackground),
            enterTransition = {
                if (isTabSwitch()) EnterTransition.None else slideInHorizontally(tween(PUSH_MILLIS, easing = pushEasing)) { it }
            },
            exitTransition = {
                if (isTabSwitch()) ExitTransition.None else slideOutHorizontally(tween(PUSH_MILLIS, easing = pushEasing)) { -it / 3 }
            },
            popEnterTransition = {
                if (isTabSwitch()) EnterTransition.None else slideInHorizontally(tween(PUSH_MILLIS, easing = pushEasing)) { -it / 3 }
            },
            popExitTransition = {
                if (isTabSwitch()) ExitTransition.None else slideOutHorizontally(tween(PUSH_MILLIS, easing = pushEasing)) { it }
            },
        ) {
            composable<HomeRoute> {
                TabRoot(navController, selected = 0) { bottomPadding ->
                    HomeScreen(
                        container = container,
                        bottomPadding = bottomPadding,
                        onNewRip = { navController.navigate(MetadataRoute()) },
                        onOpenDiscMetadata = { label -> navController.navigate(MetadataRoute(volumeLabel = label)) },
                        onOpenTmdbSettings = { navController.navigate(MetadataSettingsRoute) },
                        onOpenStorageSettings = { navController.navigate(StorageSettingsRoute) },
                        onOpenNasSettings = { navController.navigate(NasSettingsRoute) },
                        onOpenPlexSettings = { navController.navigate(PlexSettingsRoute) },
                    )
                }
            }
            composable<SettingsRoute> {
                TabRoot(navController, selected = 1) { bottomPadding ->
                    SettingsScreen(
                        container = container,
                        bottomPadding = bottomPadding,
                        onOpenQuality = { navController.navigate(QualitySettingsRoute) },
                        onOpenMetadata = { navController.navigate(MetadataSettingsRoute) },
                        onOpenStorage = { navController.navigate(StorageSettingsRoute) },
                        onOpenNas = { navController.navigate(NasSettingsRoute) },
                        onOpenPlex = { navController.navigate(PlexSettingsRoute) },
                    )
                }
            }
            composable<MetadataRoute> { entry ->
                MetadataScreen(
                    container = container,
                    volumeLabel = entry.toRoute<MetadataRoute>().volumeLabel,
                    backLabel = stringResource(R.string.app_name),
                    onBack = { navController.popIfResumed(entry) },
                    onDone = { navController.popIfResumed(entry) },
                    onOpenSettings = { navController.navigate(MetadataSettingsRoute) },
                )
            }
            composable<QualitySettingsRoute> { entry ->
                QualitySettingsScreen(container, onBack = { navController.popIfResumed(entry) })
            }
            composable<MetadataSettingsRoute> { entry ->
                MetadataSettingsScreen(container, onBack = { navController.popIfResumed(entry) })
            }
            composable<StorageSettingsRoute> { entry ->
                StorageSettingsScreen(container, onBack = { navController.popIfResumed(entry) })
            }
            composable<NasSettingsRoute> { entry ->
                NasSettingsScreen(container, onBack = { navController.popIfResumed(entry) })
            }
            composable<PlexSettingsRoute> { entry ->
                PlexSettingsScreen(container, onBack = { navController.popIfResumed(entry) })
            }
        }
    }
}

/**
 * A tab root screen with the tab bar drawn as part of the screen, so it slides away with the
 * screen on a push, as `hidesBottomBarWhenPushed` does on iOS.
 */
@Composable
private fun TabRoot(navController: NavHostController, selected: Int, content: @Composable (bottomPadding: Dp) -> Unit) {
    val items = listOf(
        TabItem(stringResource(R.string.tab_rip), HigIcons.Disc),
        TabItem(stringResource(R.string.tab_settings), HigIcons.Gear),
    )
    val routes = listOf<Any>(HomeRoute, SettingsRoute)
    Box(Modifier.fillMaxSize()) {
        content(HigMetrics.tabBarHeight)
        HigTabBar(
            items = items,
            selectedIndex = selected,
            onSelect = { index ->
                if (index != selected) {
                    navController.navigate(routes[index]) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
