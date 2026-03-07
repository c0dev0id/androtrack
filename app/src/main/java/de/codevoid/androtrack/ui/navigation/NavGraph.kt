package de.codevoid.androtrack.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.codevoid.androtrack.ui.screens.RidesScreen
import de.codevoid.androtrack.ui.screens.SettingsScreen
import de.codevoid.androtrack.ui.screens.TrackDetailScreen
import de.codevoid.androtrack.ui.screens.TrackScreen
import de.codevoid.androtrack.ui.theme.Orange600
import de.codevoid.androtrack.viewmodel.TrackingState
import de.codevoid.androtrack.viewmodel.TrackingViewModel
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Rides : Screen("rides", "Rides", Icons.Default.DirectionsBike)
    object Track : Screen("track", "Track", Icons.Default.RadioButtonChecked)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

const val TRACK_DETAIL_ROUTE = "track_detail/{filePath}"

private val bottomNavScreens = listOf(Screen.Rides, Screen.Track, Screen.Settings)

@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val trackingViewModel: TrackingViewModel = viewModel()
    val trackingState by trackingViewModel.state.collectAsState()
    val isTracking = trackingState is TrackingState.Active

    Scaffold(
        bottomBar = {
            val navBackStack by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStack?.destination?.route
            // Hide bottom bar on track detail screen
            val showBottomBar = bottomNavScreens.any { it.route == currentRoute }
            AnimatedVisibility(
                visible = showBottomBar,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    bottomNavScreens.forEach { screen ->
                        val selected = currentRoute == screen.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(Screen.Rides.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                if (screen == Screen.Track && isTracking && !selected) {
                                    BadgedBox(
                                        badge = {
                                            Badge(containerColor = Orange600)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = screen.icon,
                                            contentDescription = screen.label,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = screen.icon,
                                        contentDescription = screen.label,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            },
                            label = { Text(screen.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Rides.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Rides.route) {
                RidesScreen(
                    onOpenTrack = { filePath ->
                        val encoded = URLEncoder.encode(filePath, StandardCharsets.UTF_8.toString())
                        navController.navigate("track_detail/$encoded")
                    }
                )
            }
            composable(Screen.Track.route) {
                TrackScreen(trackingViewModel = trackingViewModel)
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
            composable(
                route = TRACK_DETAIL_ROUTE,
                arguments = listOf(navArgument("filePath") { type = NavType.StringType })
            ) { backStackEntry ->
                val encoded = backStackEntry.arguments?.getString("filePath") ?: ""
                val filePath = URLDecoder.decode(encoded, StandardCharsets.UTF_8.toString())
                TrackDetailScreen(
                    filePath = filePath,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
