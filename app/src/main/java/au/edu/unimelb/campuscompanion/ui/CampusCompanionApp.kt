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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import au.edu.unimelb.campuscompanion.auth.AuthViewModel
import au.edu.unimelb.campuscompanion.auth.AuthenticatedUser
import au.edu.unimelb.campuscompanion.data.TimetableImporter
import au.edu.unimelb.campuscompanion.data.TimetableSubscriptionStore
import au.edu.unimelb.campuscompanion.ui.model.TimetableState
import au.edu.unimelb.campuscompanion.ui.navigation.CampusDestination
import au.edu.unimelb.campuscompanion.ui.screens.AuthLoadingScreen
import au.edu.unimelb.campuscompanion.ui.screens.CompleteProfileScreen
import au.edu.unimelb.campuscompanion.ui.screens.GroupsScreen
import au.edu.unimelb.campuscompanion.ui.screens.HomeScreen
import au.edu.unimelb.campuscompanion.ui.screens.LoginScreen
import au.edu.unimelb.campuscompanion.ui.screens.ProfileScreen
import au.edu.unimelb.campuscompanion.ui.screens.ScheduleScreen
import au.edu.unimelb.campuscompanion.ui.theme.CampusCompanionTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusCompanionApp(authViewModel: AuthViewModel) {
    val authState by authViewModel.uiState.collectAsState()

    CampusCompanionTheme {
        val user = authState.user
        when {
            authState.isInitializing -> AuthLoadingScreen()
            user == null -> LoginScreen(
                state = authState,
                onGoogleSignIn = authViewModel::signInWithGoogle,
                onAppleSignIn = authViewModel::signInWithApple,
                onSendEmailCode = authViewModel::sendEmailCode,
                onVerifyEmailCode = authViewModel::verifyEmailCode,
                onResendEmailCode = authViewModel::resendEmailCode,
                onChangeEmail = authViewModel::changeEmail,
                onClearMessage = authViewModel::clearMessage
            )
            authState.requiresProfileName -> CompleteProfileScreen(
                user = user,
                state = authState,
                onSaveName = authViewModel::saveDisplayName,
                onSignOut = authViewModel::signOut,
                onClearMessage = authViewModel::clearMessage
            )
            else -> AuthenticatedCampusApp(
                user = user,
                onSignOut = authViewModel::signOut
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedCampusApp(
    user: AuthenticatedUser,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val timetableStore = remember(context) { TimetableSubscriptionStore(context) }
    val timetableImporter = remember { TimetableImporter() }
    val savedTimetableUrl = remember(user.id) { timetableStore.loadUrl(user.id) }
    var timetableState by remember(user.id) {
        mutableStateOf(
            TimetableState(
                url = savedTimetableUrl,
                isLoading = savedTimetableUrl.isNotBlank()
            )
        )
    }

    DisposableEffect(timetableImporter) {
        onDispose(timetableImporter::close)
    }

    LaunchedEffect(user.id, savedTimetableUrl) {
        if (savedTimetableUrl.isBlank()) return@LaunchedEffect

        timetableImporter.importFromUrl(savedTimetableUrl).fold(
            onSuccess = { imported ->
                timetableState = TimetableState(
                    url = savedTimetableUrl,
                    sessions = imported.sessions,
                    groups = imported.groups,
                    detectedEventCount = imported.sourceEventCount,
                    isConnected = true
                )
            },
            onFailure = { error ->
                timetableState = TimetableState(
                    url = savedTimetableUrl,
                    errorMessage = error.message
                )
            }
        )
    }

    val connectTimetable: suspend (String) -> Result<Unit> = { url ->
        val previousState = timetableState
        timetableImporter.importFromUrl(url).fold(
            onSuccess = { imported ->
                timetableStore.saveUrl(user.id, url)
                timetableState = TimetableState(
                    url = url,
                    sessions = imported.sessions,
                    groups = imported.groups,
                    detectedEventCount = imported.sourceEventCount,
                    isConnected = true
                )
                Result.success(Unit)
            },
            onFailure = { error ->
                timetableState = previousState.copy(errorMessage = error.message)
                Result.failure(error)
            }
        )
    }

    val removeTimetable = {
        timetableStore.clear(user.id)
        timetableState = TimetableState()
    }
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
            composable(CampusDestination.Home.route) {
                HomeScreen(
                    timetableState = timetableState,
                    onTimetableUrlSave = connectTimetable
                )
            }
            composable(CampusDestination.Schedule.route) {
                ScheduleScreen(
                    timetableState = timetableState,
                    onTimetableUrlSave = connectTimetable,
                    onTimetableUrlRemove = removeTimetable
                )
            }
            composable(CampusDestination.Groups.route) {
                GroupsScreen(
                    timetableState = timetableState,
                    onOpenTimetableSetup = {
                        navController.navigate(CampusDestination.Home.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(CampusDestination.Profile.route) {
                ProfileScreen(
                    user = user,
                    timetableState = timetableState,
                    onTimetableUrlSave = connectTimetable,
                    onTimetableUrlRemove = removeTimetable,
                    onSignOut = onSignOut
                )
            }
        }
    }
}
