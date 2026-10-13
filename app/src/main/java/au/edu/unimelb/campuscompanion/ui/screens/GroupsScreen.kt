package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.setValue

import androidx.compose.runtime.getValue

import au.edu.unimelb.campuscompanion.ui.text.textResources
import au.edu.unimelb.campuscompanion.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.toUserMessage
import au.edu.unimelb.campuscompanion.ui.components.GroupUpdateRow
import au.edu.unimelb.campuscompanion.ui.components.QuickActionChip
import au.edu.unimelb.campuscompanion.ui.components.SectionHeader
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.GROUP_JOIN_CODE_LENGTH
import au.edu.unimelb.campuscompanion.ui.model.NfcJoinUiState
import au.edu.unimelb.campuscompanion.ui.model.StartedGroupAccess
import au.edu.unimelb.campuscompanion.ui.model.TimetableState
import au.edu.unimelb.campuscompanion.ui.model.isValidGroupJoinCode
import au.edu.unimelb.campuscompanion.ui.model.normalizeGroupJoinCode
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.launch

@Composable
fun GroupsScreen(
    timetableState: TimetableState,
    activeGroups: List<CourseGroup>,
    foldedGroups: List<CourseGroup>,
    mutedGroupIds: Set<String>,
    onOpenTimetableSetup: () -> Unit,
    onOpenGroup: (CourseGroup) -> Unit,
    onStartGroup: suspend (name: String, courseCode: String?) -> Result<StartedGroupAccess>,
    onJoinGroup: suspend (code: String) -> Result<Unit>,
    nfcJoinState: NfcJoinUiState,
    onStartNfcJoin: () -> Unit,
    onDismissNfcJoin: () -> Unit,
    onOpenNfcSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textResources = textResources()
    var showFoldedGroups by rememberSaveable { mutableStateOf(false) }
    var showStartGroup by rememberSaveable { mutableStateOf(false) }
    var showJoinGroup by rememberSaveable { mutableStateOf(false) }
    var showQrJoin by rememberSaveable { mutableStateOf(false) }

    if (showQrJoin) {
        QrJoinDialog(
            onDismiss = { showQrJoin = false },
            onJoinGroup = onJoinGroup,
            onEnterCode = {
                showQrJoin = false
                showJoinGroup = true
            }
        )
    }

    if (showStartGroup) {
        StartGroupDialog(
            onDismiss = { showStartGroup = false },
            onStartGroup = onStartGroup
        )
    }

    if (showJoinGroup) {
        JoinGroupDialog(
            onDismiss = { showJoinGroup = false },
            onJoinGroup = onJoinGroup
        )
    }

    if (nfcJoinState !is NfcJoinUiState.Idle) {
        NfcJoinDialog(
            state = nfcJoinState,
            onDismiss = onDismissNfcJoin,
            onTryAgain = onStartNfcJoin,
            onOpenNfcSettings = onOpenNfcSettings
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = textResources.getString(R.string.groups_course_groups),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = when {
                    timetableState.isLoading -> textResources.getString(R.string.groups_checking_your_timetable_and_group_memberships)
                    timetableState.isConnected -> {
                        textResources.getString(R.string.groups_timetable_groups_are_folded_after_their_final_class)
                    }
                    else -> textResources.getString(R.string.groups_create_a_group_or_connect_your_timetable_to)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (timetableState.isSyncingGroups) {
            Text(textResources.getString(R.string.groups_joining_your_course_and_tutorial_workshop_groups))
        }
        timetableState.groupSyncError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { showStartGroup = true },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text(
                    text = textResources.getString(R.string.groups_start_group),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            OutlinedButton(
                onClick = { showJoinGroup = true },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.GroupAdd, contentDescription = null)
                Text(
                    text = textResources.getString(R.string.groups_join_group),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickActionChip(
                label = textResources.getString(R.string.groups_scan_qr),
                icon = Icons.Outlined.QrCodeScanner,
                onClick = { showQrJoin = true }
            )
            QuickActionChip(
                label = textResources.getString(R.string.label_nfc_join),
                icon = Icons.Outlined.Nfc,
                onClick = onStartNfcJoin
            )
        }

        if (timetableState.isLoading && activeGroups.isEmpty() && foldedGroups.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text(textResources.getString(R.string.groups_loading_groups))
                }
            }
        } else {
            SectionHeader(title = textResources.getString(R.string.groups_active_groups))
            if (activeGroups.isEmpty()) {
                EmptyActiveGroups(
                    timetableConnected = timetableState.isConnected,
                    hasFoldedGroups = foldedGroups.isNotEmpty(),
                    errorMessage = timetableState.errorMessage?.resolve(textResources),
                    onOpenTimetableSetup = onOpenTimetableSetup
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    activeGroups.forEach { group ->
                        GroupUpdateRow(
                            group = group,
                            muted = group.id in mutedGroupIds,
                            onClick = { onOpenGroup(group) }
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = { showFoldedGroups = !showFoldedGroups },
                modifier = Modifier.fillMaxWidth().semantics {
                    stateDescription = textResources.getString(if (showFoldedGroups) R.string.a11y_expanded else R.string.a11y_collapsed)
                }
            ) {
                Icon(Icons.Outlined.Archive, contentDescription = null)
                Text(
                    text = textResources.getString(R.string.groups_label, foldedGroups.size),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Icon(
                    imageVector = if (showFoldedGroups) {
                        Icons.Outlined.ExpandLess
                    } else {
                        Icons.Outlined.ExpandMore
                    },
                    contentDescription = if (showFoldedGroups) {
                        textResources.getString(R.string.groups_hide_folded_groups)
                    } else {
                        textResources.getString(R.string.groups_show_folded_groups)
                    }
                )
            }

            if (showFoldedGroups) {
                SectionHeader(title = textResources.getString(R.string.groups_folded_groups))
                if (foldedGroups.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Text(
                            text = textResources.getString(R.string.groups_no_folded_group_chats),
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        foldedGroups.forEach { group ->
                            GroupUpdateRow(
                                group = group,
                                muted = group.id in mutedGroupIds,
                                onClick = { onOpenGroup(group) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NfcJoinDialog(
    state: NfcJoinUiState,
    onDismiss: () -> Unit,
    onTryAgain: () -> Unit,
    onOpenNfcSettings: () -> Unit
) {
    val textResources = textResources()
    val isJoining = state is NfcJoinUiState.Joining
    val title = when (state) {
        NfcJoinUiState.Idle -> ""
        NfcJoinUiState.Waiting -> textResources.getString(R.string.groups_ready_to_join)
        NfcJoinUiState.Joining -> textResources.getString(R.string.groups_joining_group)
        NfcJoinUiState.Disabled -> textResources.getString(R.string.groups_turn_on_nfc)
        NfcJoinUiState.Unsupported -> textResources.getString(R.string.presentation_nfc_is_not_available)
        is NfcJoinUiState.Joined -> textResources.getString(R.string.groups_group_joined)
        is NfcJoinUiState.Failed -> state.title
    }
    val message = when (state) {
        NfcJoinUiState.Idle -> ""
        NfcJoinUiState.Waiting -> {
            textResources.getString(R.string.groups_hold_the_back_of_your_phone_near_the)
        }
        NfcJoinUiState.Joining -> textResources.getString(R.string.groups_checking_the_invitation_and_adding_the_group)
        NfcJoinUiState.Disabled -> textResources.getString(R.string.groups_nfc_is_switched_off_on_this_device_turn)
        NfcJoinUiState.Unsupported -> textResources.getString(R.string.groups_this_device_cannot_read_nfc_tags_you_can)
        is NfcJoinUiState.Joined -> textResources.getString(R.string.copy_now_appears_in_your_active_groups, state.groupName)
        is NfcJoinUiState.Failed -> state.message
    }

    AlertDialog(
        onDismissRequest = {
            if (!isJoining) onDismiss()
        },
        icon = {
            when (state) {
                NfcJoinUiState.Joining -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
                is NfcJoinUiState.Joined -> Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null
                )
                is NfcJoinUiState.Failed,
                NfcJoinUiState.Disabled,
                NfcJoinUiState.Unsupported -> Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null
                )
                else -> Icon(
                    imageVector = Icons.Outlined.Nfc,
                    contentDescription = null
                )
            }
        },
        title = { Text(title) },
        text = { Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
        confirmButton = {
            when (state) {
                NfcJoinUiState.Disabled -> TextButton(onClick = onOpenNfcSettings) {
                    Text(textResources.getString(R.string.groups_open_nfc_settings))
                }
                is NfcJoinUiState.Failed -> TextButton(onClick = onTryAgain) {
                    Text(textResources.getString(R.string.groups_try_again))
                }
                NfcJoinUiState.Joining -> Unit
                else -> TextButton(onClick = onDismiss) {
                    Text(if (state is NfcJoinUiState.Waiting) textResources.getString(R.string.action_cancel) else textResources.getString(R.string.course_reminder_settings_done))
                }
            }
        },
        dismissButton = {
            if (state is NfcJoinUiState.Disabled || state is NfcJoinUiState.Failed) {
                TextButton(onClick = onDismiss) {
                    Text(textResources.getString(R.string.action_cancel))
                }
            }
        }
    )
}

@Composable
private fun EmptyActiveGroups(
    timetableConnected: Boolean,
    hasFoldedGroups: Boolean,
    errorMessage: String?,
    onOpenTimetableSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textResources = textResources()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Groups,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = textResources.getString(R.string.groups_no_active_groups),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = when {
                    hasFoldedGroups -> textResources.getString(R.string.groups_your_existing_group_chats_are_in)
                    timetableConnected -> textResources.getString(R.string.groups_no_active_groups_are_available)
                    errorMessage != null -> errorMessage
                    else -> textResources.getString(R.string.groups_create_a_group_or_connect_your_timetable_on)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!timetableConnected) {
                Button(onClick = onOpenTimetableSetup) {
                    Text(textResources.getString(R.string.groups_go_to_home))
                }
            }
        }
    }
}

@Composable
private fun StartGroupDialog(
    onDismiss: () -> Unit,
    onStartGroup: suspend (name: String, courseCode: String?) -> Result<StartedGroupAccess>
) {
    val textResources = textResources()
    var name by rememberSaveable { mutableStateOf("") }
    var courseCode by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var isSubmitting by rememberSaveable { mutableStateOf(false) }
    var startedGroup by remember { mutableStateOf<StartedGroupAccess?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(startedGroup) {
        if (startedGroup != null) while (true) { now = Instant.now(); delay(1_000) }
    }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = {
            if (!isSubmitting) onDismiss()
        },
        icon = {
            if (startedGroup != null) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null)
            }
        },
        title = { Text(if (startedGroup == null) textResources.getString(R.string.groups_start_group) else textResources.getString(R.string.groups_group_started)) },
        text = {
            val result = startedGroup
            if (result == null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it.take(60)
                            errorMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(textResources.getString(R.string.groups_group_name)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = courseCode,
                        onValueChange = {
                            courseCode = it.uppercase().filterNot(Char::isWhitespace).take(9)
                            errorMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(textResources.getString(R.string.groups_course_code_optional)) },
                        placeholder = { Text(textResources.getString(R.string.label_comp90018)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                    errorMessage?.let { error ->
                        Text(
                            text = error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(textResources.getString(R.string.copy_is_ready, result.groupName))
                    if (result.joinCode != null && result.expiresAt != null && now.isBefore(result.expiresAt)) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = textResources.getString(R.string.groups_join_code),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Text(
                                        text = result.joinCode,
                                        style = MaterialTheme.typography.headlineMedium
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(result.joinCode))
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ContentCopy,
                                        contentDescription = textResources.getString(R.string.groups_copy_join_code)
                                    )
                                }
                            }
                        }
                        val remaining = Duration.between(now, result.expiresAt).seconds.coerceAtLeast(0)
                        Text(textResources.getString(R.string.groups_code_expires_in_valid_only_during_the_first, remaining / 60, (remaining % 60).toString().padStart(2, '0')))
                    } else {
                        Text(
                            text = textResources.getString(R.string.groups_the_6_character_code_is_unavailable_or_expired),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (startedGroup == null) {
                Button(
                    onClick = {
                        scope.launch {
                            isSubmitting = true
                            onStartGroup(name, courseCode.ifBlank { null }).fold(
                                onSuccess = { startedGroup = it },
                                onFailure = {
                                    errorMessage = startGroupErrorMessage(it, textResources)
                                }
                            )
                            isSubmitting = false
                        }
                    },
                    enabled = name.isNotBlank() && !isSubmitting
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(textResources.getString(R.string.groups_start))
                    }
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(textResources.getString(R.string.course_reminder_settings_done))
                }
            }
        },
        dismissButton = {
            if (startedGroup == null) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSubmitting
                ) {
                    Text(textResources.getString(R.string.action_cancel))
                }
            }
        }
    )
}

private fun startGroupErrorMessage(error: Throwable, textResources: android.content.res.Resources): String = when (error) {
    is DataError.NotFound -> {
        textResources.getString(R.string.groups_group_creation_is_not_available_on_the_connected)
    }
    else -> error.toUserMessage().body.resolve(textResources)
}

@Composable
private fun JoinGroupDialog(
    onDismiss: () -> Unit,
    onJoinGroup: suspend (code: String) -> Result<Unit>
) {
    val textResources = textResources()
    var code by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var isSubmitting by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = {
            if (!isSubmitting) onDismiss()
        },
        title = { Text(textResources.getString(R.string.groups_join_group)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = textResources.getString(R.string.groups_enter_the_6_character_code_it_works_only),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = normalizeGroupJoinCode(it)
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth().semantics { errorMessage?.let { error(it) } },
                    label = { Text(textResources.getString(R.string.groups_join_code)) },
                    placeholder = { Text(textResources.getString(R.string.label_a1b2c3)) },
                    supportingText = { Text(textResources.getString(R.string.input_code_character_count, code.length, GROUP_JOIN_CODE_LENGTH)) },
                    isError = errorMessage != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                errorMessage?.let { error ->
                    Text(
                        text = error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        isSubmitting = true
                        onJoinGroup(code).fold(
                            onSuccess = { onDismiss() },
                            onFailure = {
                                errorMessage = joinCodeErrorMessage(it, textResources)
                            }
                        )
                        isSubmitting = false
                    }
                },
                enabled = isValidGroupJoinCode(code) && !isSubmitting
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(textResources.getString(R.string.groups_join))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting
            ) {
                Text(textResources.getString(R.string.action_cancel))
            }
        }
    )
}

private fun joinCodeErrorMessage(error: Throwable, textResources: android.content.res.Resources): String = when (error) {
    is DataError.InvalidInvite -> when (error.reason) {
        DataError.InvalidInvite.Reason.Unknown -> {
            textResources.getString(R.string.groups_this_6_character_code_was_not_recognised_check)
        }
        DataError.InvalidInvite.Reason.Expired -> {
            textResources.getString(R.string.groups_this_join_code_has_expired_ask_the_group)
        }
        DataError.InvalidInvite.Reason.UsedUp -> {
            textResources.getString(R.string.groups_this_join_code_has_reached_its_usage_limit)
        }
    }
    else -> error.toUserMessage().body.resolve(textResources)
}
