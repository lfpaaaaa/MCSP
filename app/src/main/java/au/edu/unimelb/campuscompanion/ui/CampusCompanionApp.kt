package au.edu.unimelb.campuscompanion.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import au.edu.unimelb.campuscompanion.ui.navigation.CampusDestination
import au.edu.unimelb.campuscompanion.ui.screens.GroupsScreen
import au.edu.unimelb.campuscompanion.ui.screens.HomeScreen
import au.edu.unimelb.campuscompanion.ui.screens.ProfileScreen
import au.edu.unimelb.campuscompanion.ui.screens.ScheduleScreen
import au.edu.unimelb.campuscompanion.ui.theme.CampusCompanionTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusCompanionApp() {
    CampusCompanionTheme {
        val navController = rememberNavController()
        val destinations = CampusDestination.topLevelDestinations
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination
        val currentRoute = currentDestination?.route ?: CampusDestination.Home.route
        val currentScreen = destinations.firstOrNull { it.route == currentRoute } ?: CampusDestination.Home

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(currentScreen.title) },
                    colors = TopAppBarDefaults.topAppBarColors()
                )
            },
            bottomBar = {
                NavigationBar {
                    destinations.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
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
                                    imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = destination.title
                                )
                            },
                            label = { Text(destination.title) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = CampusDestination.Home.route,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(CampusDestination.Home.route) { HomeScreen() }
                composable(CampusDestination.Schedule.route) { ScheduleScreen() }
                composable(CampusDestination.Groups.route) { GroupsScreen() }
                composable(CampusDestination.Profile.route) { ProfileScreen() }
            }
        }
    }
}
