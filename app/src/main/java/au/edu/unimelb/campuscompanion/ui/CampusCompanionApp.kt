package au.edu.unimelb.campuscompanion.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
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
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.MAX_PENDING_DOCUMENTS
import au.edu.unimelb.campuscompanion.ui.model.PendingDocument
import au.edu.unimelb.campuscompanion.ui.model.TimetableState
import au.edu.unimelb.campuscompanion.ui.model.mergePendingDocuments
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
import java.io.File

private const val GROUP_CHAT_ROUTE = "group_chat/{groupId}"

private fun groupChatRoute(groupId: String): String = "group_chat/${Uri.encode(groupId)}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusCompanionApp(authViewModel: AuthViewModel) {
    val authState by authViewModel.uiState.collectAsState()
    val context = LocalContext.current
    var openGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var openGroupCourseCode by rememberSaveable { mutableStateOf<String?>(null) }
    var openGroupName by rememberSaveable { mutableStateOf<String?>(null) }
    var openGroupMembers by rememberSaveable { mutableStateOf(0) }
    var openGroupLatestMessage by rememberSaveable { mutableStateOf("") }
    var pendingDocumentUris by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var pendingDocumentNames by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var pendingDocumentSizes by rememberSaveable { mutableStateOf(arrayListOf<Long>()) }
    var pendingDocumentGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDocumentError by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var capturedCameraUri by rememberSaveable { mutableStateOf<String?>(null) }

    val pendingDocuments = pendingDocumentUris.indices.map { index ->
        PendingDocument(
            uri = pendingDocumentUris[index],
            displayName = pendingDocumentNames[index],
            sizeBytes = pendingDocumentSizes[index]
        )
    }

    fun savePendingDocuments(documents: List<PendingDocument>) {
        pendingDocumentUris = ArrayList(documents.map(PendingDocument::uri))
        pendingDocumentNames = ArrayList(documents.map(PendingDocument::displayName))
        pendingDocumentSizes = ArrayList(documents.map(PendingDocument::sizeBytes))
    }

    fun clearPendingDocuments() {
        savePendingDocuments(emptyList())
        pendingDocumentGroupId = null
        pendingDocumentError = null
    }

    fun clearCameraCapture() {
        pendingCameraUri = null
        cameraGroupId = null
        capturedCameraUri = null
    }

    fun removePendingDocument(uri: String) {
        savePendingDocuments(pendingDocuments.filterNot { it.uri == uri })
        pendingDocumentError = null
    }

    fun clearOpenGroup() {
        openGroupId = null
        openGroupCourseCode = null
        openGroupName = null
        openGroupMembers = 0
        openGroupLatestMessage = ""
        clearPendingDocuments()
        clearCameraCapture()
    }

    fun saveOpenGroup(group: CourseGroup) {
        if (pendingDocumentGroupId != group.id) clearPendingDocuments()
        if (cameraGroupId != group.id) clearCameraCapture()
        openGroupId = group.id
        openGroupCourseCode = group.courseCode
        openGroupName = group.name
        openGroupMembers = group.members
        openGroupLatestMessage = group.latestMessage
    }

    val restoredGroup = openGroupId?.let { groupId ->
        val courseCode = openGroupCourseCode ?: return@let null
        val name = openGroupName ?: return@let null
        CourseGroup(
            id = groupId,
            courseCode = courseCode,
            name = name,
            members = openGroupMembers,
            unreadCount = 0,
            latestMessage = openGroupLatestMessage,
            latestFileName = null,
            privateContentEnabled = false
        )
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val selected = uris.map { uri -> resolveDocument(context, uri) }
            val result = mergePendingDocuments(pendingDocuments, selected)
            savePendingDocuments(result.documents)

            val errors = mutableListOf<String>()
            if (result.rejectedTooLarge > 0) {
                errors += "Each document must be 10 MB or smaller."
            }
            if (result.rejectedByLimit > 0) {
                errors += "You can attach up to 9 documents."
            }
            pendingDocumentError = errors.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { saved ->
        val capturedUri = pendingCameraUri
        pendingCameraUri = null
        if (saved && capturedUri != null) {
            capturedCameraUri = capturedUri
        } else {
            cameraGroupId = null
            capturedCameraUri = null
        }
    }

    CampusCompanionTheme {
        val user = authState.user
        LaunchedEffect(authState.isInitializing, user?.id) {
            if (!authState.isInitializing && user == null) {
                clearOpenGroup()
            }
        }
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
                onSignOut = {
                    clearOpenGroup()
                    authViewModel.signOut()
                },
                onClearMessage = authViewModel::clearMessage
            )
            else -> AuthenticatedCampusApp(
                user = user,
                openGroupId = openGroupId,
                restoredGroup = restoredGroup,
                pendingDocuments = pendingDocuments.takeIf {
                    pendingDocumentGroupId == openGroupId
                }.orEmpty(),
                pendingDocumentError = pendingDocumentError.takeIf {
                    pendingDocumentGroupId == openGroupId
                },
                capturedCameraUri = capturedCameraUri.takeIf {
                    cameraGroupId == openGroupId
                },
                onPickDocuments = { groupId ->
                    pendingDocumentError = null
                    if (pendingDocuments.size >= MAX_PENDING_DOCUMENTS) {
                        pendingDocumentError = "You can attach up to 9 documents."
                    } else {
                        pendingDocumentGroupId = groupId
                        filePicker.launch(arrayOf("*/*"))
                    }
                },
                onPendingDocumentRemoved = ::removePendingDocument,
                onPendingDocumentsCleared = ::clearPendingDocuments,
                onTakeGroupPhoto = { groupId ->
                    val uri = createCameraImageUri(context)
                    cameraGroupId = groupId
                    pendingCameraUri = uri.toString()
                    cameraLauncher.launch(uri)
                },
                onCapturedCameraPhotoConsumed = ::clearCameraCapture,
                onGroupOpened = ::saveOpenGroup,
                onGroupClosed = ::clearOpenGroup,
                onSignOut = {
                    clearOpenGroup()
                    authViewModel.signOut()
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedCampusApp(
    user: AuthenticatedUser,
    openGroupId: String?,
    restoredGroup: CourseGroup?,
    pendingDocuments: List<PendingDocument>,
    pendingDocumentError: String?,
    capturedCameraUri: String?,
    onPickDocuments: (String) -> Unit,
    onPendingDocumentRemoved: (String) -> Unit,
    onPendingDocumentsCleared: () -> Unit,
    onTakeGroupPhoto: (String) -> Unit,
    onCapturedCameraPhotoConsumed: () -> Unit,
    onGroupOpened: (CourseGroup) -> Unit,
    onGroupClosed: () -> Unit,
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
    val homeDestination = destinations.first()
    val initialDestination = remember {
        openGroupId?.let(::groupChatRoute) ?: homeDestination.route
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route ?: homeDestination.route
    val currentScreen = destinations.firstOrNull { it.route == currentRoute } ?: homeDestination
    val isGroupChat = currentRoute == GROUP_CHAT_ROUTE
    val selectedGroup = backStackEntry
        ?.arguments
        ?.getString("groupId")
        ?.let { groupId ->
            timetableState.groups.firstOrNull { it.id == groupId }
                ?: restoredGroup?.takeIf { it.id == groupId }
        }

    fun openGroup(group: CourseGroup) {
        onGroupOpened(group)
        navController.navigate(groupChatRoute(group.id)) {
            launchSingleTop = true
        }
    }

    fun closeGroup() {
        onGroupClosed()
        if (!navController.navigateUp()) {
            navController.navigate(CampusDestination.Groups.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        }
    }

    BackHandler(enabled = isGroupChat) {
        closeGroup()
    }

    LaunchedEffect(openGroupId, currentRoute) {
        val groupId = openGroupId ?: return@LaunchedEffect
        if (currentRoute != GROUP_CHAT_ROUTE) {
            navController.navigate(groupChatRoute(groupId)) {
                launchSingleTop = true
            }
        }
    }

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
                        IconButton(onClick = ::closeGroup) {
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
            startDestination = initialDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(CampusDestination.Home.route) {
                HomeScreen(
                    timetableState = timetableState,
                    travelPreferences = travelPreferences,
                    onTimetableUrlSave = connectTimetable,
                    onOpenGroup = { group ->
                        openGroup(group)
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
                        openGroup(group)
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
                val cachedGroup = restoredGroup?.takeIf { it.id == groupId }
                when {
                    group != null -> GroupChatScreen(
                        group = group,
                        pendingDocuments = pendingDocuments,
                        pendingDocumentError = pendingDocumentError,
                        capturedCameraUri = capturedCameraUri,
                        onPickDocuments = { onPickDocuments(group.id) },
                        onPendingDocumentRemoved = onPendingDocumentRemoved,
                        onPendingDocumentsCleared = onPendingDocumentsCleared,
                        onTakePhoto = { onTakeGroupPhoto(group.id) },
                        onCapturedCameraPhotoConsumed = onCapturedCameraPhotoConsumed
                    )
                    timetableState.isLoading && cachedGroup != null -> GroupChatScreen(
                        group = cachedGroup,
                        pendingDocuments = pendingDocuments,
                        pendingDocumentError = pendingDocumentError,
                        capturedCameraUri = capturedCameraUri,
                        onPickDocuments = { onPickDocuments(cachedGroup.id) },
                        onPendingDocumentRemoved = onPendingDocumentRemoved,
                        onPendingDocumentsCleared = onPendingDocumentsCleared,
                        onTakePhoto = { onTakeGroupPhoto(cachedGroup.id) },
                        onCapturedCameraPhotoConsumed = onCapturedCameraPhotoConsumed
                    )
                    timetableState.isLoading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                    else -> {
                        LaunchedEffect(groupId) {
                            closeGroup()
                        }
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

private fun createCameraImageUri(context: Context): Uri {
    val cameraDirectory = File(context.cacheDir, "camera").apply { mkdirs() }
    val imageFile = File.createTempFile("group-photo-", ".jpg", cameraDirectory)
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        imageFile
    )
}

private fun resolveDocument(
    context: Context,
    uri: Uri
): PendingDocument {
    var displayName: String? = null
    var sizeBytes = -1L
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameColumn >= 0) displayName = cursor.getString(nameColumn)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) sizeBytes = cursor.getLong(sizeColumn)
            }
        }
    }
    if (sizeBytes < 0L) {
        sizeBytes = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length
            } ?: -1L
        }.getOrDefault(-1L)
    }
    return PendingDocument(
        uri = uri.toString(),
        displayName = displayName?.takeIf(String::isNotBlank) ?: "Shared file",
        sizeBytes = sizeBytes
    )
}
