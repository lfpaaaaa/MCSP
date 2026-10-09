package au.edu.unimelb.campuscompanion.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.compose.runtime.rememberCoroutineScope
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
import au.edu.unimelb.campuscompanion.auth.SupabaseProvider
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.data.TimetableImporter
import au.edu.unimelb.campuscompanion.data.TimetableSubscriptionStore
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.TravelPreferencesStore
import au.edu.unimelb.campuscompanion.data.toUserMessage
import au.edu.unimelb.campuscompanion.data.model.Group
import au.edu.unimelb.campuscompanion.data.model.GroupInvite
import au.edu.unimelb.campuscompanion.data.model.GroupSummary
import au.edu.unimelb.campuscompanion.nfc.NfcInviteHostSession
import au.edu.unimelb.campuscompanion.nfc.NfcInviteReader
import au.edu.unimelb.campuscompanion.push.NotificationTaps
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.GroupChatPreferences
import au.edu.unimelb.campuscompanion.ui.model.GroupOrigin
import au.edu.unimelb.campuscompanion.ui.model.MAX_PENDING_DOCUMENTS
import au.edu.unimelb.campuscompanion.ui.model.NfcJoinUiState
import au.edu.unimelb.campuscompanion.ui.model.NfcShareUiState
import au.edu.unimelb.campuscompanion.ui.model.PendingDocument
import au.edu.unimelb.campuscompanion.ui.model.QrShareUiState
import au.edu.unimelb.campuscompanion.ui.model.StartedGroupAccess
import au.edu.unimelb.campuscompanion.ui.model.TimetableState
import au.edu.unimelb.campuscompanion.ui.model.foldedOverrideAfterEdit
import au.edu.unimelb.campuscompanion.ui.model.isFolded
import au.edu.unimelb.campuscompanion.ui.model.isValidGroupJoinCode
import au.edu.unimelb.campuscompanion.ui.model.mergePendingDocuments
import au.edu.unimelb.campuscompanion.ui.navigation.CampusDestination
import au.edu.unimelb.campuscompanion.ui.components.RequestLocationPermissionOnFirstUse
import au.edu.unimelb.campuscompanion.ui.screens.AuthLoadingScreen
import au.edu.unimelb.campuscompanion.ui.screens.CompleteProfileScreen
import au.edu.unimelb.campuscompanion.ui.screens.GroupChatScreen
import au.edu.unimelb.campuscompanion.ui.screens.GroupSettingsDialog
import au.edu.unimelb.campuscompanion.ui.screens.GroupsScreen
import au.edu.unimelb.campuscompanion.ui.screens.HomeScreen
import au.edu.unimelb.campuscompanion.ui.screens.InviteQrDialog
import au.edu.unimelb.campuscompanion.ui.screens.LoginScreen
import au.edu.unimelb.campuscompanion.ui.screens.NfcShareDialog
import au.edu.unimelb.campuscompanion.ui.screens.ProfileScreen
import au.edu.unimelb.campuscompanion.ui.screens.ScheduleScreen
import au.edu.unimelb.campuscompanion.ui.screens.TimetableGroupScreen
import au.edu.unimelb.campuscompanion.ui.theme.CampusCompanionTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    var openGroupOrigin by rememberSaveable { mutableStateOf(GroupOrigin.Timetable.name) }
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
        openGroupOrigin = GroupOrigin.Timetable.name
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
        openGroupOrigin = group.origin.name
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
            privateContentEnabled = false,
            origin = GroupOrigin.entries.firstOrNull { it.name == openGroupOrigin }
                ?: GroupOrigin.Timetable
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

    val groupRepository = remember { AppRepositories.groups }
    val inviteRepository = remember { AppRepositories.invites }
    val pendingJoinToken by AppRepositories.joinLinks.pendingToken.collectAsState()
    val tappedNotification by NotificationTaps.pending.collectAsState()
    val nfcInviteDelivered by NfcInviteHostSession.delivered.collectAsState()
    val nfcAdapter = remember(context) {
        context.getSystemService(NfcManager::class.java)?.defaultAdapter
    }
    val activity = remember(context) { context.findActivity() }
    val supportsNfcHostCardEmulation = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
    }
    val scope = rememberCoroutineScope()
    var nfcJoinState by remember(user.id) {
        mutableStateOf<NfcJoinUiState>(NfcJoinUiState.Idle)
    }
    var nfcShareState by remember(user.id) {
        mutableStateOf<NfcShareUiState>(NfcShareUiState.Idle)
    }
    var qrShareState by remember(user.id) {
        mutableStateOf<QrShareUiState>(QrShareUiState.Idle)
    }
    val syncedGroupSummaries by groupRepository.observeMyGroups()
        .collectAsState(initial = emptyList())
    var groupSyncReady by remember(user.id) { mutableStateOf(false) }
    LaunchedEffect(user.id) {
        if (SupabaseProvider.isConfigured) {
            groupSyncReady = false
            groupRepository.refresh().onSuccess {
                groupSyncReady = true
            }
        }
    }
    val syncedGroups = remember(syncedGroupSummaries, user.id, groupSyncReady) {
        if (!SupabaseProvider.isConfigured || !groupSyncReady) {
            emptyList()
        } else {
            syncedGroupSummaries.map { summary -> summary.toCourseGroup(user.id) }
        }
    }
    val allGroups = remember(timetableState.groups, syncedGroups) {
        // A class from the timetable is only a placeholder until a real group carries its course code.
        val linkedCourseCodes = syncedGroups.mapNotNullTo(mutableSetOf()) { group ->
            group.courseCode.takeIf(String::isNotBlank)?.uppercase()
        }
        val placeholders = timetableState.groups.filterNot { it.courseCode.uppercase() in linkedCourseCodes }
        (placeholders + syncedGroups).distinctBy(CourseGroup::id)
    }
    val groupPreferencesStore = remember(context, user.id) {
        GroupChatPreferencesStore(context, user.id)
    }
    var groupPreferencesVersion by remember(user.id) { mutableStateOf(0) }
    var currentTime by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(user.id) {
        while (true) {
            delay(60_000)
            currentTime = ZonedDateTime.now()
        }
    }
    val groupPreferences = remember(allGroups, groupPreferencesVersion, user.id) {
        allGroups.associate { group ->
            group.id to groupPreferencesStore.load(group.id)
        }
    }
    val foldedGroups = remember(
        allGroups,
        groupPreferences,
        displayedTimetable.sessions,
        currentTime
    ) {
        allGroups.filter { group ->
            group.isFolded(
                preferences = groupPreferences[group.id] ?: GroupChatPreferences(),
                sessions = displayedTimetable.sessions,
                now = currentTime
            )
        }
    }
    val foldedGroupIds = remember(foldedGroups) { foldedGroups.mapTo(mutableSetOf(), CourseGroup::id) }
    val activeGroups = remember(allGroups, foldedGroupIds) {
        allGroups.filterNot { it.id in foldedGroupIds }
    }
    val mutedGroupIds = remember(groupPreferences) {
        groupPreferences.filterValues(GroupChatPreferences::muted).keys
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
    var showGroupSettings by rememberSaveable { mutableStateOf(false) }
    val selectedGroup = backStackEntry
        ?.arguments
        ?.getString("groupId")
        ?.let { groupId ->
            allGroups.firstOrNull { it.id == groupId }
                ?: restoredGroup?.takeIf { it.id == groupId }
        }
    val selectedPreferences = selectedGroup?.let { group ->
        groupPreferences[group.id] ?: groupPreferencesStore.load(group.id)
    }

    fun openGroup(group: CourseGroup) {
        onGroupOpened(group)
        navController.navigate(groupChatRoute(group.id)) {
            launchSingleTop = true
        }
    }

    fun navigateToDestination(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun navigateToGroups() = navigateToDestination(CampusDestination.Groups.route)

    fun startNfcJoin() {
        NfcInviteHostSession.clear()
        nfcShareState = NfcShareUiState.Idle
        nfcJoinState = when {
            nfcAdapter == null -> NfcJoinUiState.Unsupported
            !nfcAdapter.isEnabled -> NfcJoinUiState.Disabled
            else -> NfcJoinUiState.Waiting
        }
    }

    fun stopNfcShare() {
        NfcInviteHostSession.clear()
        nfcShareState = NfcShareUiState.Idle
    }

    fun startQrShare(group: CourseGroup) {
        showGroupSettings = false
        qrShareState = QrShareUiState.CreatingInvite
        scope.launch {
            inviteRepository.createInvite(group.id).fold(
                onSuccess = { invite ->
                    qrShareState = QrShareUiState.Ready(
                        groupName = group.name,
                        joinUri = invite.joinUri,
                        expiresAt = invite.expiresAt
                    )
                },
                onFailure = { error ->
                    val message = error.toUserMessage()
                    qrShareState = QrShareUiState.Failed(message.title, message.body)
                }
            )
        }
    }

    // A scanned invite joins through the same path as a link that opened the app; a scanned
    // six-character code is typed in on the user's behalf.
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents?.trim() ?: return@rememberLauncherForActivityResult
        if (AppRepositories.joinLinks.offer(contents)) return@rememberLauncherForActivityResult
        val code = GroupInvite.joinCodeFromText(contents)
        if (code == null) {
            nfcJoinState = NfcJoinUiState.Failed(
                title = "Not an invitation",
                message = "That QR code is not a Campus Companion invitation. Ask a group member to show theirs."
            )
            return@rememberLauncherForActivityResult
        }
        nfcJoinState = NfcJoinUiState.Joining
        scope.launch {
            inviteRepository.joinWithToken(code).fold(
                onSuccess = { group ->
                    groupSyncReady = true
                    nfcJoinState = NfcJoinUiState.Joined(group.name)
                },
                onFailure = { error ->
                    val message = error.toUserMessage()
                    nfcJoinState = NfcJoinUiState.Failed(message.title, message.body)
                }
            )
        }
    }

    fun startQrScan() {
        NfcInviteHostSession.clear()
        nfcShareState = NfcShareUiState.Idle
        qrScanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Point the camera at a Campus Companion invitation")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
        )
    }

    fun startNfcShare(group: CourseGroup) {
        showGroupSettings = false
        nfcJoinState = NfcJoinUiState.Idle
        NfcInviteHostSession.clear()

        when {
            nfcAdapter == null || !supportsNfcHostCardEmulation -> {
                nfcShareState = NfcShareUiState.Unsupported
            }
            !nfcAdapter.isEnabled -> {
                nfcShareState = NfcShareUiState.Disabled
            }
            else -> {
                nfcShareState = NfcShareUiState.CreatingInvite
                scope.launch {
                    inviteRepository.createInvite(group.id).fold(
                        onSuccess = { invite ->
                            NfcInviteHostSession.publish(invite.joinUri)
                            nfcShareState = NfcShareUiState.Ready(
                                groupName = group.name,
                                expiresAt = invite.expiresAt
                            )
                        },
                        onFailure = { error ->
                            val message = error.toUserMessage()
                            nfcShareState = NfcShareUiState.Failed(
                                title = message.title,
                                message = message.body
                            )
                        }
                    )
                }
            }
        }
    }

    fun openNfcSettings() {
        nfcJoinState = NfcJoinUiState.Idle
        stopNfcShare()
        runCatching {
            context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        }.recoverCatching {
            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    fun closeGroup() {
        onGroupClosed()
        // The list shows the latest message and unread count, which this chat has just changed.
        if (SupabaseProvider.isConfigured) scope.launch { groupRepository.refresh() }
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

    DisposableEffect(nfcJoinState, nfcAdapter, activity) {
        val shouldRead = nfcJoinState is NfcJoinUiState.Waiting
        if (!shouldRead || nfcAdapter == null || activity == null) {
            onDispose { }
        } else {
            val callback = NfcAdapter.ReaderCallback { tag ->
                NfcInviteReader.readInviteUri(tag).fold(
                    onSuccess = { inviteUri ->
                        AppRepositories.joinLinks.offer(inviteUri)
                    },
                    onFailure = { error ->
                        activity.runOnUiThread {
                            nfcJoinState = NfcJoinUiState.Failed(
                                title = "Could not read invitation",
                                message = error.message
                                    ?: "Hold the phones together and try again."
                            )
                        }
                    }
                )
            }
            val readerEnabled = runCatching {
                nfcAdapter.enableReaderMode(
                    activity,
                    callback,
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    null
                )
            }.isSuccess
            if (!readerEnabled) {
                nfcJoinState = NfcJoinUiState.Failed(
                    title = "NFC could not start",
                    message = "Keep Campus Companion open and try again."
                )
            }
            onDispose {
                if (readerEnabled) {
                    runCatching { nfcAdapter.disableReaderMode(activity) }
                }
            }
        }
    }

    DisposableEffect(user.id) {
        onDispose { NfcInviteHostSession.clear() }
    }

    val readyNfcShare = nfcShareState as? NfcShareUiState.Ready
    LaunchedEffect(readyNfcShare?.expiresAt) {
        val ready = readyNfcShare ?: return@LaunchedEffect
        val remainingMillis = Duration.between(Instant.now(), ready.expiresAt)
            .toMillis()
            .coerceAtLeast(0L)
        delay(remainingMillis)
        if (nfcShareState == ready) {
            NfcInviteHostSession.clear()
            nfcShareState = NfcShareUiState.Failed(
                title = "Invitation expired",
                message = "Create a new NFC invitation and try again."
            )
        }
    }

    LaunchedEffect(nfcInviteDelivered) {
        if (!nfcInviteDelivered) return@LaunchedEffect
        val ready = nfcShareState as? NfcShareUiState.Ready ?: return@LaunchedEffect
        NfcInviteHostSession.clear()
        nfcShareState = NfcShareUiState.Shared(ready.groupName)
    }

    // The travel engine keeps the saved lead time in hand on top of the travel time.
    LaunchedEffect(travelPreferences.reminderLeadMinutes) {
        travel.updateLeadMinutes(travelPreferences.reminderLeadMinutes)
    }

    // A tapped notification opens its chat (once the groups are known) or the Schedule screen.
    LaunchedEffect(tappedNotification, allGroups, groupSyncReady) {
        when (val target = tappedNotification) {
            null -> Unit
            is NotificationTaps.Target.Schedule -> {
                NotificationTaps.clear()
                navigateToDestination(CampusDestination.Schedule.route)
            }
            is NotificationTaps.Target.GroupChat -> {
                val group = allGroups.firstOrNull { it.id == target.groupId }
                when {
                    group != null -> {
                        NotificationTaps.clear()
                        openGroup(group)
                    }
                    // The groups have loaded and this one is not among them any more.
                    groupSyncReady || !SupabaseProvider.isConfigured -> NotificationTaps.clear()
                }
            }
        }
    }

    LaunchedEffect(pendingJoinToken, user.id) {
        val token = pendingJoinToken ?: return@LaunchedEffect
        onGroupClosed()
        navigateToGroups()
        nfcJoinState = NfcJoinUiState.Joining

        inviteRepository.joinWithToken(token).fold(
            onSuccess = { group ->
                groupSyncReady = true
                nfcJoinState = NfcJoinUiState.Joined(group.name)
            },
            onFailure = { error ->
                val message = error.toUserMessage()
                nfcJoinState = NfcJoinUiState.Failed(
                    title = message.title,
                    message = message.body
                )
            }
        )
        AppRepositories.joinLinks.clear()
    }

    LaunchedEffect(openGroupId, currentRoute) {
        val groupId = openGroupId ?: return@LaunchedEffect
        if (currentRoute != GROUP_CHAT_ROUTE) {
            navController.navigate(groupChatRoute(groupId)) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(currentRoute) {
        if (!isGroupChat) showGroupSettings = false
        // The list's previews and unread counts come from the server; fetch them when it is shown.
        if (currentRoute == CampusDestination.Groups.route && SupabaseProvider.isConfigured) {
            groupRepository.refresh()
        }
    }

    if (qrShareState !is QrShareUiState.Idle) {
        InviteQrDialog(
            state = qrShareState,
            onDismiss = { qrShareState = QrShareUiState.Idle },
            onNewCode = {
                selectedGroup?.let(::startQrShare) ?: run { qrShareState = QrShareUiState.Idle }
            }
        )
    }

    if (nfcShareState !is NfcShareUiState.Idle) {
        NfcShareDialog(
            state = nfcShareState,
            onDismiss = ::stopNfcShare,
            onTryAgain = {
                selectedGroup?.let(::startNfcShare) ?: stopNfcShare()
            },
            onOpenNfcSettings = ::openNfcSettings
        )
    }

    if (showGroupSettings && selectedGroup != null && selectedPreferences != null) {
        GroupSettingsDialog(
            group = selectedGroup,
            initialFolded = selectedGroup.id in foldedGroupIds,
            initialMuted = selectedPreferences.muted,
            initialDisplayName = selectedPreferences.displayName.ifBlank { user.profileName },
            onDismiss = { showGroupSettings = false },
            onInviteWithNfc = { startNfcShare(selectedGroup) },
            onShowQrCode = { startQrShare(selectedGroup) },
            onReplaceJoinCode = { groupRepository.resetJoinCode(selectedGroup.id) },
            onSave = { folded, muted, displayName ->
                val foldedOverride = foldedOverrideAfterEdit(
                    existingOverride = selectedPreferences.foldedOverride,
                    initialFolded = selectedGroup.id in foldedGroupIds,
                    selectedFolded = folded
                )
                groupPreferencesStore.save(
                    groupId = selectedGroup.id,
                    foldedOverride = foldedOverride,
                    muted = muted,
                    displayName = displayName
                )
                groupPreferencesVersion += 1
                showGroupSettings = false
                if (folded) closeGroup()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isGroupChat) {
                        Column {
                            Text(
                                selectedGroup?.courseCode
                                    ?.takeIf(String::isNotBlank)
                                    ?: selectedGroup?.name
                                    ?: "Group"
                            )
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
                actions = {
                    if (isGroupChat && selectedGroup != null) {
                        IconButton(onClick = { showGroupSettings = true }) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "Group settings"
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
                    timetableState = displayedTimetable.copy(
                        // Real course groups come before the timetable placeholders.
                        groups = activeGroups
                            .filter { group -> group.origin == GroupOrigin.Timetable || group.courseCode.isNotBlank() }
                            .sortedBy { group -> group.origin == GroupOrigin.Timetable }
                    ),
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
                    travelPreferences = travelPreferences,
                    onReminderPreferencesChange = { enabled, leadMinutes ->
                        updateTravelPreferences(
                            travelPreferences.copy(remindersEnabled = enabled, reminderLeadMinutes = leadMinutes)
                        )
                    },
                    onTimetableUrlSave = connectTimetable,
                    onTimetableUrlRemove = removeTimetable
                )
            }
            composable(CampusDestination.Groups.route) {
                GroupsScreen(
                    timetableState = displayedTimetable,
                    activeGroups = activeGroups,
                    foldedGroups = foldedGroups,
                    mutedGroupIds = mutedGroupIds,
                    onOpenGroup = { group ->
                        openGroup(group)
                    },
                    onStartGroup = { name, courseCode ->
                        // The server gives every new group a six-character code that does not expire.
                        groupRepository.createGroup(name, courseCode).map { group ->
                            StartedGroupAccess(
                                groupName = group.name,
                                joinCode = group.joinCode?.takeIf(::isValidGroupJoinCode),
                                expiresAt = null
                            )
                        }
                    },
                    onJoinGroup = { code ->
                        inviteRepository.joinWithToken(code).map {
                            groupSyncReady = true
                            Unit
                        }
                    },
                    nfcJoinState = nfcJoinState,
                    onScanQr = ::startQrScan,
                    onStartNfcJoin = ::startNfcJoin,
                    onDismissNfcJoin = {
                        nfcJoinState = NfcJoinUiState.Idle
                    },
                    onOpenNfcSettings = ::openNfcSettings,
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
                val group = allGroups.firstOrNull { it.id == groupId }
                val cachedGroup = restoredGroup?.takeIf { it.id == groupId }
                // After a restart the timetable and the server-side groups load separately; the chat
                // stays open from the saved group until both have arrived.
                val groupsStillLoading = timetableState.isLoading ||
                    (SupabaseProvider.isConfigured && !groupSyncReady)
                when {
                    group != null && group.origin == GroupOrigin.Timetable -> TimetableGroupScreen(
                        group = group,
                        onStartGroup = {
                            // The timetable title ends with the session type ("…, Lecture1"), which is
                            // not part of the group's name.
                            val groupName = group.name.substringBefore(",").trim().ifBlank { group.courseCode }.take(60)
                            groupRepository.createGroup(groupName, group.courseCode).map { created ->
                                groupSyncReady = true
                                openGroup(created.toCourseGroup(currentUserId = user.id, memberCount = 1))
                            }
                        },
                        onGoToGroups = ::navigateToGroups
                    )
                    group != null -> GroupChatScreen(
                        group = group,
                        currentUserId = user.id,
                        pendingDocuments = pendingDocuments,
                        pendingDocumentError = pendingDocumentError,
                        capturedCameraUri = capturedCameraUri,
                        onPickDocuments = { onPickDocuments(group.id) },
                        onPendingDocumentRemoved = onPendingDocumentRemoved,
                        onPendingDocumentsCleared = onPendingDocumentsCleared,
                        onTakePhoto = { onTakeGroupPhoto(group.id) },
                        onCapturedCameraPhotoConsumed = onCapturedCameraPhotoConsumed
                    )
                    groupsStillLoading && cachedGroup != null -> GroupChatScreen(
                        group = cachedGroup,
                        currentUserId = user.id,
                        pendingDocuments = pendingDocuments,
                        pendingDocumentError = pendingDocumentError,
                        capturedCameraUri = capturedCameraUri,
                        onPickDocuments = { onPickDocuments(cachedGroup.id) },
                        onPendingDocumentRemoved = onPendingDocumentRemoved,
                        onPendingDocumentsCleared = onPendingDocumentsCleared,
                        onTakePhoto = { onTakeGroupPhoto(cachedGroup.id) },
                        onCapturedCameraPhotoConsumed = onCapturedCameraPhotoConsumed
                    )
                    groupsStillLoading -> Box(
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

/** A group the user has just created, before its summary has been fetched. */
private fun Group.toCourseGroup(currentUserId: String, memberCount: Int): CourseGroup = CourseGroup(
    id = id,
    courseCode = courseCode.orEmpty(),
    name = name,
    members = memberCount,
    unreadCount = 0,
    latestMessage = "No messages yet.",
    latestFileName = null,
    privateContentEnabled = privateContentEnabled,
    origin = if (createdBy == currentUserId) GroupOrigin.CreatedByUser else GroupOrigin.Joined,
    joinCode = joinCode
)

private fun GroupSummary.toCourseGroup(currentUserId: String): CourseGroup = CourseGroup(
    id = group.id,
    courseCode = group.courseCode.orEmpty(),
    name = group.name,
    members = memberCount,
    unreadCount = unreadCount,
    latestMessage = latestMessagePreview ?: "No messages yet.",
    latestFileName = latestFileName,
    privateContentEnabled = group.privateContentEnabled,
    origin = if (group.createdBy == currentUserId) {
        GroupOrigin.CreatedByUser
    } else {
        GroupOrigin.Joined
    },
    joinCode = group.joinCode
)

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
