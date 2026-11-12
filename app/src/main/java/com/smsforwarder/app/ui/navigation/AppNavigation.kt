package com.smsforwarder.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.smsforwarder.app.ui.dashboard.DashboardScreen
import com.smsforwarder.app.ui.dashboard.DashboardViewModel
import com.smsforwarder.app.ui.history.HistoryScreen
import com.smsforwarder.app.ui.history.HistoryViewModel
import com.smsforwarder.app.ui.rules.RuleEditScreen
import com.smsforwarder.app.ui.rules.RulesScreen
import com.smsforwarder.app.ui.rules.RulesViewModel
import com.smsforwarder.app.ui.settings.SettingsScreen
import com.smsforwarder.app.ui.settings.SettingsViewModel

@Composable
fun AppNavigation(
    navController: NavHostController,
    dashboardViewModel: DashboardViewModel,
    rulesViewModel: RulesViewModel,
    historyViewModel: HistoryViewModel,
    settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bottomBarScreens = listOf(
        Screen.Dashboard,
        Screen.Rules,
        Screen.History,
        Screen.Settings
    )

    val showBottomBar = bottomBarScreens.any { it.route == currentRoute }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomBarScreens.forEach { screen ->
                        NavigationBarItem(
                            selected = currentRoute == screen.route,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                screen.icon?.let { Icon(it, contentDescription = screen.title) }
                            },
                            label = { Text(screen.title) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(viewModel = dashboardViewModel)
            }
            composable(Screen.Rules.route) {
                RulesScreen(
                    viewModel = rulesViewModel,
                    onNavigateToEditRule = { ruleId ->
                        navController.navigate(Screen.RuleEdit.createRoute(ruleId))
                    }
                )
            }
            composable(
                route = Screen.RuleEdit.route,
                arguments = listOf(
                    navArgument("ruleId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val ruleId = backStackEntry.arguments?.getString("ruleId")
                RuleEditScreen(
                    ruleId = ruleId,
                    viewModel = rulesViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.History.route) {
                HistoryScreen(viewModel = historyViewModel)
            }
            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = settingsViewModel)
            }
        }
    }
}
