package com.example.heatradar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.heatradar.R
import com.example.heatradar.core.ui.theme.HeatRadarTheme
import com.example.heatradar.feature.appdetail.AppDetailScreen
import com.example.heatradar.feature.dashboard.DashboardScreen
import com.example.heatradar.feature.settings.SettingsScreen
import com.example.heatradar.feature.trends.TrendsScreen
import dagger.hilt.android.AndroidEntryPoint

sealed class Screen(val route: String, val labelRes: Int, val icon: @Composable () -> Unit) {
    data object Dashboard : Screen(
        route = "dashboard",
        labelRes = R.string.title_dashboard,
        icon = { Icon(Icons.Default.Home, contentDescription = null) }
    )

    data object Trends : Screen(
        route = "trends",
        labelRes = R.string.title_trends,
        icon = { Icon(Icons.Default.TrendingUp, contentDescription = null) }
    )

    data object Settings : Screen(
        route = "settings",
        labelRes = R.string.title_settings,
        icon = { Icon(Icons.Default.Settings, contentDescription = null) }
    )
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HeatRadarTheme {
                HeatRadarApp()
            }
        }
    }
}

@Composable
fun HeatRadarApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val bottomRoutes = listOf(Screen.Dashboard.route, Screen.Trends.route, Screen.Settings.route)
    val showBottomBar = currentDestination?.route in bottomRoutes

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                HeatRadarBottomBar(navController, currentDestination?.route)
            }
        }
    ) { innerPadding ->
        HeatRadarNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
fun HeatRadarBottomBar(navController: NavController, currentRoute: String?) {
    val items = listOf(Screen.Dashboard, Screen.Trends, Screen.Settings)

    NavigationBar {
        items.forEach { screen ->
            val selected = currentRoute == screen.route
            NavigationBarItem(
                icon = screen.icon,
                label = { Text(stringResource(screen.labelRes)) },
                selected = selected,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(Screen.Dashboard.route) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}

@Composable
fun HeatRadarNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route,
        modifier = modifier
    ) {
        composable(Screen.Dashboard.route) {
            DashboardScreen(
                onAppClick = { packageName ->
                    navController.navigate("appDetail/$packageName")
                },
                onNavigateToTrends = { navController.navigate(Screen.Trends.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }
        composable(
            route = "appDetail/{packageName}",
            arguments = listOf(navArgument("packageName") { type = NavType.StringType })
        ) { backStackEntry ->
            val packageName = backStackEntry.arguments?.getString("packageName") ?: ""
            AppDetailScreen(
                packageName = packageName,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Trends.route) {
            TrendsScreen()
        }
        composable(Screen.Settings.route) {
            SettingsScreen()
        }
    }
}
