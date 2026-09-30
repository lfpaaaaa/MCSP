package au.edu.unimelb.campuscompanion.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import au.edu.unimelb.campuscompanion.auth.AuthViewModel
import au.edu.unimelb.campuscompanion.auth.AuthenticatedUser
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.data.TimetableImporter
import au.edu.unimelb.campuscompanion.data.TimetableSubscriptionStore
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.TravelPreferencesStore
import au.edu.unimelb.campuscompanion.ui.model.TimetableState
import au.edu.unimelb.campuscompanion.ui.navigation.CampusDestination
import au.edu.unimelb.campuscompanion.ui.components.RequestLocationPermissionOnFirstUse
import au.edu.unimelb.campuscompanion.ui.screens.AuthLoadingScreen
import au.edu.unimelb.campuscompanion.ui.screens.CompleteProfileScreen
import au.edu.unimelb.campuscompanion.ui.screens.GroupChatScreen
import au.edu.unimelb.campuscompanion.ui.screens.GroupsScreen
import au.edu.unimelb.campuscompanion.ui.screens.HomeScreen
import au.edu.unimelb.campuscompanion.ui.screens.LoginScreen
import au.edu.unimelb.campuscompanion.ui.screens.ProfileScreen
import au.edu.unimelb.campuscompanion.ui.screens.ScheduleScreen
import au.edu.unimelb.campuscompanion.ui.theme.CampusCompanionTheme

private const val GROUP_CHAT_ROUTE = "group_chat/{groupId}"

private fun groupChatRoute(groupId: String): String = "group_chat/${Uri.encode(groupId)}"

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
    RequestLocationPermissionOnFirstUse()

    val context = LocalContext.current
    val timetableStore = remember(context) { TimetableSubscriptionStore(context) }
    val timetableImporter = remember { TimetableImporter() }
    val travelPreferencesStore = remember(context) { TravelPreferencesStore(context) }
    var travelPreferences by remember(user.id) {
        mutableStateOf(travelPreferencesStore.load())
    }
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

    val travel = AppRepositories.travel
    val travelSnapshot by travel.snapshot.collectAsState()
    LaunchedEffect(timetableState.sessions) {
        travel.updateSessions(timetableState.sessions)
    }
    DisposableEffect(travel) {
        onDispose { travel.updateSessions(emptyList()) }
    }
    // The screens see the next class with its travel time filled in.
    val displayedTimetable = remember(timetableState, travelSnapshot) {
        timetableState.copy(sessions = travelSnapshot.applyTo(timetableState.sessions))
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
    val updateTravelPreferences: (TravelPreferences) -> Unit = { updatedPreferences ->
        travelPreferencesStore.save(updatedPreferences)
        travelPreferences = updatedPreferences
    }
    val navController = rememberNavController()
    val destinations = CampusDestination.topLevelDestinations
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route ?: CampusDestination.Home.route
    val currentScreen = destinations.firstOrNull { it.route == currentRoute } ?: CampusDestination.Home
    val isGroupChat = currentRoute == GROUP_CHAT_ROUTE
    val selectedGroup = backStackEntry
        ?.arguments
        ?.getString("groupId")
        ?.let { groupId -> timetableState.groups.firstOrNull { it.id == groupId } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isGroupChat) {
                        Column {
                            Text(selectedGroup?.courseCode ?: "Group")
                            selectedGroup?.let { group ->
                                Text(
                                    text = if (group.members > 0) {
                                        "${group.members} members"
                                    } else {
                                        group.name
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Text(currentScreen.title)
                    }
                },
                navigationIcon = {
                    if (isGroupChat) {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Back to groups"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        },
        bottomBar = {
            if (!isGroupChat) {
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
                    travelPreferences = travelPreferences,
                    onTimetableUrlSave = connectTimetable,
                    onOpenGroup = { group ->
                        navController.navigate(groupChatRoute(group.id))
                    }
                )
            }
            composable(CampusDestination.Schedule.route) {
                ScheduleScreen(
                    timetableState = displayedTimetable,
                    onTimetableUrlSave = connectTimetable,
                    onTimetableUrlRemove = removeTimetable
                )
            }
            composable(CampusDestination.Groups.route) {
                GroupsScreen(
                    timetableState = displayedTimetable,
                    onOpenGroup = { group ->
                        navController.navigate(groupChatRoute(group.id))
                    },
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
            composable(
                route = GROUP_CHAT_ROUTE,
                arguments = listOf(
                    navArgument("groupId") { type = NavType.StringType }
                )
            ) { entry ->
                val groupId = entry.arguments?.getString("groupId")
                val group = timetableState.groups.firstOrNull { it.id == groupId }
                if (group != null) {
                    GroupChatScreen(group = group)
                } else {
                    LaunchedEffect(groupId) {
                        navController.navigateUp()
                    }
                }
            }
            composable(CampusDestination.Profile.route) {
                ProfileScreen(
                    user = user,
                    timetableState = timetableState,
                    travelPreferences = travelPreferences,
                    onTimetableUrlSave = connectTimetable,
                    onTimetableUrlRemove = removeTimetable,
                    onTravelPreferencesChange = updateTravelPreferences,
                    onSignOut = onSignOut
                )
            }
        }
    }
}
